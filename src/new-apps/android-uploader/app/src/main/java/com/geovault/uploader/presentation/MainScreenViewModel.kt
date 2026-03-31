package com.geovault.uploader.presentation

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.ServerUrlContract
import com.geovault.common.update.AppVersionChecker
import com.geovault.common.update.VersionCheckRequest
import com.geovault.common.update.VersionCheckSnackbarHelper
import com.geovault.uploader.BuildConfig
import com.geovault.uploader.MainActivity
import com.geovault.uploader.data.AuthRepository
import com.geovault.uploader.data.FileMetadataRepository
import com.geovault.uploader.data.UploaderPreferences
import com.geovault.uploader.data.UploadRepository
import com.geovault.uploader.data.ValidationRepository
import com.geovault.uploader.domain.FilenamePolicy
import com.geovault.uploader.model.UploadResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.random.Random

data class MainScreenState(
    val isAuthenticated: Boolean = false,
    val serverUrl: String = "",
    val isConnecting: Boolean = false,
    val oauthUrl: String? = null,
    val isValidationMode: Boolean = true,
    val validationTitle: String = "Configuration Required",
    val validationMessage: String = "Please configure settings first",
    val isValidationLoading: Boolean = false,
    val fileUri: Uri? = null,
    val originalFilename: String = "",
    val editedFilename: String = "",
    val suffixPreview: String = "",
    val isUploading: Boolean = false,
    val statusMessage: String = "",
    val importantMessage: String? = null,
    val updatePromptMessage: String? = null,
    val updatePromptUrl: String? = null
)

class MainScreenViewModel(application: Application) : AndroidViewModel(application) {
    private companion object {
        const val TAG = "MainScreenViewModel"
    }
    private val appContext = application.applicationContext
    private val preferences = UploaderPreferences.getInstance(appContext)
    private val fileMetadataRepository = FileMetadataRepository(appContext.contentResolver)
    private val validationRepository = ValidationRepository(appContext)
    private val uploadRepository = UploadRepository(appContext, appContext.contentResolver)
    private val authRepository = AuthRepository(appContext)

    private val _state = MutableStateFlow(MainScreenState())
    val state: StateFlow<MainScreenState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.settings.collect { settings ->
                val current = _state.value
                _state.value = current.copy(
                    suffixPreview = buildSuffixPreview(current.editedFilename, settings.suffixEnabled)
                )
            }
        }
    }

    fun initialize(intent: Intent?) {
        refreshAuthState()
        handleIntent(intent)
        intent?.getStringExtra(MainActivity.EXTRA_OAUTH_ERROR)?.let { msg ->
            _state.value = _state.value.copy(importantMessage = msg)
        }
        if (_state.value.isAuthenticated && _state.value.isValidationMode) {
            validate()
        }
        if (_state.value.isAuthenticated) {
            checkForUpdate()
        }
    }

    fun onHostResumed() {
        val wasAuthenticated = _state.value.isAuthenticated
        refreshAuthState()
        val isAuthenticated = _state.value.isAuthenticated
        if (!wasAuthenticated && isAuthenticated) {
            _state.value = _state.value.copy(isConnecting = false, oauthUrl = null, importantMessage = null)
            if (_state.value.isValidationMode) {
                validate()
            }
            checkForUpdate()
        }
    }

    fun onAuthServerUrlChanged(url: String) {
        _state.value = _state.value.copy(serverUrl = url)
        GeovaultAuthManager.setServerUrl(appContext, url)
    }

    fun connectAuth() {
        val normalized = GeovaultAuthManager.normalizeServerUrl(_state.value.serverUrl)
        if (normalized.isBlank()) {
            _state.value = _state.value.copy(importantMessage = "Server URL is required. Connect your account to sign in.")
            return
        }
        _state.value = _state.value.copy(isConnecting = true, importantMessage = "Connecting to server…")
        GeovaultAuthManager.resolveServerUrlToCanonical(normalized) { result ->
            result.fold(
                onSuccess = { resolved ->
                    GeovaultAuthManager.setServerUrl(appContext, resolved, commit = true)
                    val (verifier, challenge) = GeovaultAuthManager.generatePkcePair()
                    val state = (1..16).map { "abcdef0123456789"[Random.nextInt(16)] }.joinToString("")
                    GeovaultAuthManager.savePkceState(appContext, verifier, state)
                    val url = GeovaultAuthManager.buildAuthorizeUrl(resolved, challenge, state)
                    _state.value = _state.value.copy(oauthUrl = url, importantMessage = null)
                },
                onFailure = {
                    _state.value = _state.value.copy(
                        isConnecting = false,
                        importantMessage = "Could not reach server. Check URL and connection."
                    )
                }
            )
        }
    }

    fun onOauthUrlConsumed() {
        _state.value = _state.value.copy(oauthUrl = null, isConnecting = false)
    }

    fun onFilenameChanged(newName: String) {
        _state.value = _state.value.copy(
            editedFilename = newName,
            suffixPreview = buildSuffixPreview(newName, preferences.isSuffixEnabled())
        )
    }

    fun onFileChosen(fileUri: Uri) {
        populateUploadState(fileUri)
    }

    fun clearImportantMessage() {
        _state.value = _state.value.copy(importantMessage = null)
    }

    fun clearUpdatePrompt() {
        _state.value = _state.value.copy(updatePromptMessage = null, updatePromptUrl = null)
    }

    fun validate() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                validationTitle = "Validating API Key…",
                validationMessage = "Connecting to server…",
                isValidationLoading = true
            )
            val result = validationRepository.validateConnection()
            val title = if (result.startsWith("✓")) "Connected" else "Validation Failed"
            _state.value = _state.value.copy(
                validationTitle = title,
                validationMessage = result,
                isValidationLoading = false
            )
        }
    }

    fun uploadCurrentFile(onSuccessClose: () -> Unit) {
        val snapshot = _state.value
        val uri = snapshot.fileUri ?: run {
            _state.value = snapshot.copy(statusMessage = "No file provided")
            return
        }
        val userFilename = snapshot.editedFilename.trim()
        if (userFilename.isEmpty()) {
            _state.value = snapshot.copy(statusMessage = "Please enter a filename")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isUploading = true, statusMessage = "Uploading…")
            val finalFilename = FilenamePolicy.withOptionalSuffix(userFilename, preferences.isSuffixEnabled())
            val result: UploadResult = uploadRepository.upload(uri, finalFilename)
            if (result.success) {
                _state.value = _state.value.copy(isUploading = false, statusMessage = "Upload successful!")
                onSuccessClose()
            } else {
                _state.value = _state.value.copy(
                    isUploading = false,
                    statusMessage = result.errorMessage ?: "Upload failed",
                    importantMessage = result.errorMessage ?: "Upload failed"
                )
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val fileUri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            if (fileUri != null) {
                populateUploadState(fileUri)
                return
            }
        }
        _state.value = _state.value.copy(isValidationMode = true, fileUri = null)
    }

    private fun populateUploadState(fileUri: Uri) {
        val originalName = fileMetadataRepository.filenameFromUri(fileUri)
        _state.value = _state.value.copy(
            isValidationMode = false,
            fileUri = fileUri,
            originalFilename = originalName,
            editedFilename = originalName,
            suffixPreview = buildSuffixPreview(originalName, preferences.isSuffixEnabled()),
            statusMessage = "",
            importantMessage = null
        )
    }

    private fun buildSuffixPreview(filename: String, suffixEnabled: Boolean): String {
        val finalName = FilenamePolicy.withOptionalSuffix(filename, suffixEnabled)
        return "Will be saved as: $finalName"
    }

    private fun checkForUpdate() {
        viewModelScope.launch {
            val result = AppVersionChecker().checkForUpdateIfDue(
                context = appContext,
                rateLimitKey = "uploader",
                request = VersionCheckRequest(
                    appName = "GeoVault Uploader",
                    localFullCommitSha = BuildConfig.GIT_COMMIT_SHA
                )
            )
            val prompt = VersionCheckSnackbarHelper.buildPrompt(result)
            if (prompt != null) {
                _state.value = _state.value.copy(
                    updatePromptMessage = prompt.message,
                    updatePromptUrl = prompt.releaseUrl
                )
            }
        }
    }

    private fun refreshAuthState() {
        val resolvedServer = GeovaultAuthManager.getServerUrl(appContext).ifBlank {
            ServerUrlContract.getServerUrlsFromOtherApps(appContext).singleOrNull().orEmpty()
        }
        Log.i(TAG, GeovaultAuthManager.getAuthDebugSnapshot(appContext))
        _state.value = _state.value.copy(
            isAuthenticated = authRepository.isLoggedIn(),
            serverUrl = resolvedServer
        )
        Log.i(TAG, "refreshAuthState isAuthenticated=${_state.value.isAuthenticated} serverBlank=${resolvedServer.isBlank()}")
    }
}
