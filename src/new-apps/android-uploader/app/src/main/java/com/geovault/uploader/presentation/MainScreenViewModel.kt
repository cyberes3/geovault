package com.geovault.uploader.presentation

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.common.ui.snackbar.GeoVaultSnackbarModel
import com.geovault.common.update.AppVersionChecker
import com.geovault.common.update.VersionCheckRequest
import com.geovault.common.update.VersionCheckSnackbarPresenter
import com.geovault.uploader.BuildConfig
import com.geovault.uploader.MainActivity
import com.geovault.uploader.data.AuthRepository
import com.geovault.uploader.data.AuthRepository.OAuthPreparationResult
import com.geovault.uploader.data.ValidationOutcome
import com.geovault.uploader.di.UploaderAppServices
import com.geovault.uploader.domain.FilenamePolicy
import com.geovault.uploader.model.UploadResult
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MainScreenState(
    val isAuthenticated: Boolean = false,
    val serverUrl: String = "",
    val isConnecting: Boolean = false,
    val oauthUrl: String? = null,
    val isValidationMode: Boolean = true,
    val validationTitle: String = "Configuration Required",
    val validationMessage: String = "Please configure settings first",
    val isValidationLoading: Boolean = false,
    val validationOutcome: ValidationOutcome = ValidationOutcome.Info,
    val fileUri: Uri? = null,
    val originalFilename: String = "",
    val editedFilename: String = "",
    val suffixPreview: String = "",
    val isUploading: Boolean = false,
    val statusMessage: String = "",
    val importantSnackbar: GeoVaultSnackbarModel? = null,
    val updateSnackbar: GeoVaultSnackbarModel? = null,
    val updateReleaseUrl: String? = null
)

class MainScreenViewModel(
    application: Application,
    services: UploaderAppServices = UploaderAppServices.from(application)
) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val preferences = services.uploaderPreferences()
    private val fileMetadataRepository = services.fileMetadataRepository()
    private val validationRepository = services.validationRepository()
    private val uploadRepository = services.uploadRepository()
    private val authRepository: AuthRepository = services.authRepository()

    private val _state = MutableStateFlow(MainScreenState())
    val state: StateFlow<MainScreenState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.settings.collect { settings ->
                _state.update { current ->
                    current.copy(
                        suffixPreview = buildSuffixPreview(current.editedFilename, settings.suffixEnabled)
                    )
                }
            }
        }
    }

    fun initialize(intent: Intent?) {
        refreshAuthState()
        handleIntent(intent)
        intent?.getStringExtra(MainActivity.EXTRA_OAUTH_ERROR)?.let { msg ->
            _state.update {
                it.copy(importantSnackbar = GeoVaultSnackbarModel(id = newImportantId(), message = msg))
            }
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
            _state.update { it.copy(isConnecting = false, oauthUrl = null, importantSnackbar = null) }
            if (_state.value.isValidationMode) {
                validate()
            }
            checkForUpdate()
        }
    }

    fun onAuthServerUrlChanged(url: String) {
        _state.update { it.copy(serverUrl = url) }
        authRepository.setServerUrl(url)
    }

    fun connectAuth() {
        _state.update {
            it.copy(
                isConnecting = true,
                importantSnackbar = GeoVaultSnackbarModel(id = newImportantId(), message = "Connecting to server…")
            )
        }
        viewModelScope.launch {
            when (val result = authRepository.prepareOAuthConnection(_state.value.serverUrl)) {
                is OAuthPreparationResult.Ready -> {
                    _state.update { it.copy(oauthUrl = result.oauthUrl, importantSnackbar = null) }
                }

                is OAuthPreparationResult.InvalidServerUrl -> {
                    _state.update {
                        it.copy(
                            isConnecting = false,
                            importantSnackbar = GeoVaultSnackbarModel(
                                id = newImportantId(),
                                message = result.message
                            )
                        )
                    }
                }

                is OAuthPreparationResult.UnreachableServer -> {
                    _state.update {
                        it.copy(
                            isConnecting = false,
                            importantSnackbar = GeoVaultSnackbarModel(
                                id = newImportantId(),
                                message = result.message
                            )
                        )
                    }
                }
            }
        }
    }

    fun onOauthUrlConsumed() {
        _state.update { it.copy(oauthUrl = null, isConnecting = false) }
    }

    fun onFilenameChanged(newName: String) {
        _state.update {
            it.copy(
                editedFilename = newName,
                suffixPreview = buildSuffixPreview(newName, preferences.isSuffixEnabled())
            )
        }
    }

    fun onFileChosen(fileUri: Uri) {
        populateUploadState(fileUri)
    }

    fun clearImportantMessage() {
        _state.update { it.copy(importantSnackbar = null) }
    }

    fun clearUpdatePrompt() {
        _state.update { it.copy(updateSnackbar = null, updateReleaseUrl = null) }
    }

    fun validate() {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    validationTitle = "Validating API Key…",
                    validationMessage = "Connecting to server…",
                    isValidationLoading = true,
                    validationOutcome = ValidationOutcome.Loading
                )
            }
            val result = validationRepository.validateConnection()
            _state.update {
                it.copy(
                    validationTitle = result.title,
                    validationMessage = result.message,
                    isValidationLoading = false,
                    validationOutcome = result.outcome
                )
            }
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
                val err = result.errorMessage ?: "Upload failed"
                _state.value = _state.value.copy(
                    isUploading = false,
                    statusMessage = err,
                    importantSnackbar = GeoVaultSnackbarModel(id = newImportantId(), message = err)
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
        _state.update { it.copy(isValidationMode = true, fileUri = null) }
    }

    private fun populateUploadState(fileUri: Uri) {
        val originalName = fileMetadataRepository.filenameFromUri(fileUri)
        _state.update {
            it.copy(
                isValidationMode = false,
                fileUri = fileUri,
                originalFilename = originalName,
                editedFilename = originalName,
                suffixPreview = buildSuffixPreview(originalName, preferences.isSuffixEnabled()),
                statusMessage = "",
                importantSnackbar = null
            )
        }
    }

    private fun buildSuffixPreview(filename: String, suffixEnabled: Boolean): String {
        val finalName = FilenamePolicy.withOptionalSuffix(filename, suffixEnabled)
        return "Will be saved as: $finalName"
    }

    private fun checkForUpdate() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                AppVersionChecker().checkForUpdateIfDue(
                    context = appContext,
                    rateLimitKey = "uploader",
                    request = VersionCheckRequest(
                        appName = "GeoVault Uploader",
                        localFullCommitSha = BuildConfig.GIT_COMMIT_SHA
                    )
                )
            }
            val (model, url) = VersionCheckSnackbarPresenter.snackbarAndReleaseUrl(result)
            if (model != null && url != null) {
                _state.update { it.copy(updateSnackbar = model, updateReleaseUrl = url) }
            }
        }
    }

    private fun refreshAuthState() {
        val resolvedServer = authRepository.getConfiguredServerUrlOrPeerDefault()
        _state.update {
            it.copy(
                isAuthenticated = authRepository.isLoggedIn(),
                serverUrl = resolvedServer
            )
        }
    }

    private fun newImportantId(): String = UUID.randomUUID().toString()
}
