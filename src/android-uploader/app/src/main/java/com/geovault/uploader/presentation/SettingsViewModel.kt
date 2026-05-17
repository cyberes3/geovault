package com.geovault.uploader.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.common.AppResetFlow
import com.geovault.common.auth.AuthConnectCoordinator
import com.geovault.common.auth.CommonInitialAuthController
import com.geovault.uploader.di.UploaderAppServices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class SettingsState(
    val serverUrl: String = "",
    val suffixEnabled: Boolean = true,
    val isLoggedIn: Boolean = false,
    val loggedInText: String = "",
    val isConnecting: Boolean = false,
    val infoMessage: String? = null,
    val oauthUrl: String? = null
)

class SettingsViewModel(
    application: Application,
    services: UploaderAppServices,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        UploaderAppServices.from(application)
    )
    private val appContext = application.applicationContext
    private val prefs = services.uploaderPreferences()
    private val authController: CommonInitialAuthController = services.initialAuthController()
    private val authConnect = AuthConnectCoordinator(viewModelScope, authController)

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            prefs.settings.collect { settings ->
                _state.value = _state.value.copy(
                    suffixEnabled = settings.suffixEnabled
                )
            }
        }
    }

    fun initialize() {
        refreshAuthState()
    }

    fun onHostResumed() {
        refreshAuthState()
        if (!_state.value.isLoggedIn) {
            _state.value = _state.value.copy(isConnecting = false, oauthUrl = null)
        }
    }

    private fun refreshAuthState() {
        val server = authController.getConfiguredServerUrlOrPeerDefault()
        val loggedIn = authController.isLoggedIn()
        _state.value = _state.value.copy(
            serverUrl = server,
            isLoggedIn = loggedIn,
            loggedInText = if (loggedIn) {
                authController.getCachedUserEmail()?.takeIf { it.isNotBlank() }?.let { "Logged in as $it" } ?: "Logged in"
            } else {
                ""
            }
        )
        if (loggedIn && authController.getCachedUserEmail().isNullOrBlank()) {
            authController.fetchUserEmail { email ->
                val text = if (!email.isNullOrBlank()) "Logged in as $email" else ""
                _state.value = _state.value.copy(loggedInText = text)
            }
        }
    }

    fun onServerUrlChanged(url: String) {
        _state.value = _state.value.copy(serverUrl = url)
        authController.setServerUrl(url)
    }

    fun onSuffixChanged(enabled: Boolean) {
        _state.value = _state.value.copy(suffixEnabled = enabled)
        viewModelScope.launch {
            prefs.setSuffixEnabled(enabled)
        }
    }

    fun connect() {
        authConnect.launch(
            rawServerUrl = _state.value.serverUrl,
            onConnecting = {
                _state.value = _state.value.copy(isConnecting = true, infoMessage = null)
            },
            onResult = ::applyOAuthPreparationResult,
        )
    }

    private fun applyOAuthPreparationResult(result: CommonInitialAuthController.OAuthPreparationResult) {
        when (result) {
            is CommonInitialAuthController.OAuthPreparationResult.Ready -> {
                _state.value = _state.value.copy(
                    oauthUrl = result.oauthUrl,
                    infoMessage = null,
                )
            }
            is CommonInitialAuthController.OAuthPreparationResult.InvalidServerUrl -> {
                _state.value = _state.value.copy(
                    isConnecting = false,
                    infoMessage = result.message,
                )
            }
            is CommonInitialAuthController.OAuthPreparationResult.UnreachableServer -> {
                _state.value = _state.value.copy(
                    isConnecting = false,
                    infoMessage = result.message,
                )
            }
        }
    }

    fun onOauthUrlConsumed() {
        _state.value = _state.value.copy(oauthUrl = null)
    }

    fun disconnect(mainActivityClass: Class<*>) {
        viewModelScope.launch {
            authController.revokeCurrentSessionTokens()
            AppResetFlow.execute(
                context = appContext,
                reason = AppResetFlow.Reason.MANUAL_SIGN_OUT,
                mainActivityClass = mainActivityClass
            )
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(infoMessage = null)
    }
}
