package com.geovault.tracker.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.common.auth.CommonInitialAuthController
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.runtime.RuntimeTrigger
import com.geovault.tracker.TrackingCommandFacade
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainScreenState(
    val isAuthenticated: Boolean = false,
    val serverUrl: String = "",
    val isConnecting: Boolean = false,
    val oauthUrl: String? = null,
    val infoMessage: String? = null,
)

class MainScreenViewModel(application: Application) : AndroidViewModel(application) {

    private val authController: CommonInitialAuthController =
        TrackerAppServices.from(application).initialAuthController()

    private val _state = MutableStateFlow(MainScreenState())
    val state: StateFlow<MainScreenState> = _state.asStateFlow()

    fun initialize() {
        refreshAuthState()
    }

    fun requestStartTracking() {
        TrackingCommandFacade.requestStart(
            getApplication(),
            trigger = RuntimeTrigger.EXPLICIT_START,
            reason = "home_debug_start"
        )
    }

    fun requestStopTracking() {
        TrackingCommandFacade.requestStop(getApplication(), reason = "home_debug_stop")
    }

    fun onHostResumed() {
        refreshAuthState()
    }

    fun onAuthServerUrlChanged(url: String) {
        _state.update { it.copy(serverUrl = url) }
        authController.setServerUrl(url)
    }

    fun connectAuth() {
        _state.update { it.copy(isConnecting = true, infoMessage = "Connecting to server...") }
        viewModelScope.launch {
            when (val result = authController.prepareOAuthConnection(_state.value.serverUrl)) {
                is CommonInitialAuthController.OAuthPreparationResult.Ready -> {
                    _state.update {
                        it.copy(
                            serverUrl = authController.getConfiguredServerUrlOrPeerDefault(),
                            oauthUrl = result.oauthUrl,
                            isConnecting = false,
                            infoMessage = null,
                        )
                    }
                }
                is CommonInitialAuthController.OAuthPreparationResult.InvalidServerUrl -> {
                    _state.update { it.copy(isConnecting = false, infoMessage = result.message) }
                }
                is CommonInitialAuthController.OAuthPreparationResult.UnreachableServer -> {
                    _state.update { it.copy(isConnecting = false, infoMessage = result.message) }
                }
            }
        }
    }

    fun onOauthUrlConsumed() {
        _state.update { it.copy(oauthUrl = null, isConnecting = false) }
    }

    fun showExternalError(message: String) {
        _state.update { it.copy(infoMessage = message) }
    }

    fun clearInfoMessage() {
        _state.update { it.copy(infoMessage = null) }
    }

    private fun refreshAuthState() {
        val server = authController.getConfiguredServerUrlOrPeerDefault()
        val loggedIn = server.isNotBlank() && authController.isLoggedIn()
        _state.update {
            it.copy(
                isAuthenticated = loggedIn,
                serverUrl = server,
            )
        }
    }
}
