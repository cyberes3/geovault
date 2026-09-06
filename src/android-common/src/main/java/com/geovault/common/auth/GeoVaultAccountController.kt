package com.geovault.common.auth

import android.content.Context
import com.geovault.common.bootstrap.AppResetFlow
import com.geovault.common.bootstrap.AuthStateCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GeoVaultAccountUiState(
    val serverUrl: String = "",
    val isLoggedIn: Boolean = false,
    val userEmail: String? = null,
    val loggedInText: String = "",
    val isConnecting: Boolean = false,
    val infoMessage: String? = null,
    val oauthUrl: String? = null,
) {
    val displayEmail: String
        get() = userEmail?.takeIf { it.isNotBlank() } ?: "Authenticated User"
}

class GeoVaultAccountController(
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val authController: CommonInitialAuthController,
) {
    private val authConnect = AuthConnectCoordinator(scope, authController)
    private val _state = MutableStateFlow(GeoVaultAccountUiState())
    val state: StateFlow<GeoVaultAccountUiState> = _state.asStateFlow()

    fun initialize() {
        GeoVaultAuthConnectErrors.setOnClearListener {
            _state.update { it.copy(infoMessage = null) }
        }
        _state.update { it.copy(isLoggedIn = AuthStateCache.isAuthenticated || it.isLoggedIn) }
        refreshAuthState()
    }

    fun onHostResumed() {
        refreshAuthState()
        if (!_state.value.isLoggedIn) {
            _state.update { it.copy(isConnecting = false, oauthUrl = null) }
        }
    }

    fun onServerUrlChanged(url: String) {
        _state.update { it.copy(serverUrl = url) }
        authController.setServerUrl(url)
    }

    fun connect() {
        authConnect.launch(
            rawServerUrl = _state.value.serverUrl,
            onConnecting = {
                clearConnectError()
                _state.update { it.copy(isConnecting = true) }
            },
            onResult = ::applyOAuthPreparationResult,
        )
    }

    fun onOauthUrlConsumed() {
        _state.update { it.copy(oauthUrl = null) }
    }

    fun showExternalError(message: String) {
        _state.update { it.copy(isConnecting = false, oauthUrl = null) }
        publishConnectError(message)
    }

    fun clearInfoMessage() {
        clearConnectError()
    }

    private fun clearConnectError() {
        GeoVaultAuthConnectErrors.clear(notifyListener = false)
        _state.update { it.copy(infoMessage = null) }
    }

    private fun publishConnectError(message: String) {
        GeoVaultAuthConnectErrors.show(message)
        _state.update { it.copy(infoMessage = message) }
    }

    fun disconnect(mainActivityClass: Class<*>) {
        scope.launch {
            authController.revokeCurrentSessionTokens()
            AppResetFlow.execute(
                context = appContext,
                reason = AppResetFlow.Reason.MANUAL_SIGN_OUT,
                mainActivityClass = mainActivityClass,
            )
        }
    }

    private fun applyOAuthPreparationResult(result: CommonInitialAuthController.OAuthPreparationResult) {
        when (result) {
            is CommonInitialAuthController.OAuthPreparationResult.Ready -> {
                clearConnectError()
                _state.update {
                    it.copy(
                        serverUrl = authController.getConfiguredServerUrlOrPeerDefault(),
                        oauthUrl = result.oauthUrl,
                    )
                }
            }
            is CommonInitialAuthController.OAuthPreparationResult.InvalidServerUrl -> {
                _state.update { it.copy(isConnecting = false) }
                publishConnectError(result.message)
            }
            is CommonInitialAuthController.OAuthPreparationResult.UnreachableServer -> {
                _state.update { it.copy(isConnecting = false) }
                publishConnectError(result.message)
            }
        }
    }

    private fun refreshAuthState() {
        val server = authController.getConfiguredServerUrlOrPeerDefault()
        val loggedIn = server.isNotBlank() && authController.isLoggedIn()
        val cachedEmail = authController.getCachedUserEmail()?.takeIf { it.isNotBlank() }
        _state.update {
            it.copy(
                serverUrl = server,
                isLoggedIn = loggedIn,
                userEmail = cachedEmail,
                loggedInText = when {
                    loggedIn && cachedEmail != null -> "Logged in as $cachedEmail"
                    loggedIn -> "Logged in"
                    else -> ""
                },
                oauthUrl = if (loggedIn) null else it.oauthUrl,
            )
        }
        if (loggedIn && cachedEmail == null) {
            authController.fetchUserEmail { email ->
                if (!email.isNullOrBlank()) {
                    _state.update { current ->
                        current.copy(
                            userEmail = email,
                            loggedInText = "Logged in as $email",
                        )
                    }
                }
            }
        }
    }
}
