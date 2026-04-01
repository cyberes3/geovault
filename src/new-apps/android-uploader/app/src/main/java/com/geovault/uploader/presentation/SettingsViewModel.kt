package com.geovault.uploader.presentation

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.common.AppResetFlow
import com.geovault.common.GeovaultAuthManager
import com.geovault.uploader.data.AuthRepository
import com.geovault.uploader.data.AuthRepository.OAuthPreparationResult
import com.geovault.uploader.data.UploaderPreferences
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

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private companion object {
        const val TAG = "SettingsViewModel"
    }
    private val appContext = application.applicationContext
    private val prefs = UploaderPreferences.getInstance(appContext)
    private val auth = AuthRepository(appContext)

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
    }

    private fun refreshAuthState() {
        val server = auth.getConfiguredServerUrlOrPeerDefault()
        Log.i(TAG, GeovaultAuthManager.getAuthDebugSnapshot(appContext))
        val loggedIn = auth.isLoggedIn()
        _state.value = _state.value.copy(
            serverUrl = server,
            isLoggedIn = loggedIn,
            loggedInText = if (loggedIn) {
                auth.getCachedUserEmail()?.takeIf { it.isNotBlank() }?.let { "Logged in as $it" } ?: "Logged in"
            } else {
                ""
            }
        )
        Log.i(TAG, "refreshAuthState isLoggedIn=$loggedIn serverBlank=${server.isBlank()} cachedEmailBlank=${auth.getCachedUserEmail().isNullOrBlank()}")
        if (loggedIn && auth.getCachedUserEmail().isNullOrBlank()) {
            auth.fetchUserEmail { email ->
                val text = if (!email.isNullOrBlank()) "Logged in as $email" else ""
                _state.value = _state.value.copy(loggedInText = text)
            }
        }
    }

    fun onServerUrlChanged(url: String) {
        _state.value = _state.value.copy(serverUrl = url)
        auth.setServerUrl(url)
    }

    fun onSuffixChanged(enabled: Boolean) {
        _state.value = _state.value.copy(suffixEnabled = enabled)
        viewModelScope.launch {
            prefs.setSuffixEnabled(enabled)
        }
    }

    fun connect() {
        _state.value = _state.value.copy(isConnecting = true, infoMessage = "Connecting to server…")
        viewModelScope.launch {
            when (val result = auth.prepareOAuthConnection(_state.value.serverUrl)) {
                is OAuthPreparationResult.Ready -> {
                    _state.value = _state.value.copy(oauthUrl = result.oauthUrl, infoMessage = null)
                }

                is OAuthPreparationResult.InvalidServerUrl -> {
                    _state.value = _state.value.copy(
                        isConnecting = false,
                        infoMessage = result.message
                    )
                }

                is OAuthPreparationResult.UnreachableServer -> {
                    _state.value = _state.value.copy(
                        isConnecting = false,
                        infoMessage = result.message
                    )
                }
            }
        }
    }

    fun onOauthUrlConsumed() {
        _state.value = _state.value.copy(oauthUrl = null, isConnecting = false)
    }

    fun disconnect(mainActivityClass: Class<*>) {
        viewModelScope.launch {
            auth.revokeCurrentSessionTokens()
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
