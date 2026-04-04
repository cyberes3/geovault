package com.geovault.tracker.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.common.AppResetFlow
import com.geovault.common.auth.CommonInitialAuthController
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.settings.TrackerSettings
import com.geovault.tracker.settings.TrackerSettingsLoadState
import com.geovault.tracker.settings.TrackerSettingsRepository
import com.geovault.tracker.settings.TrackerTrackingProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsState(
    val serverUrl: String = "",
    val isLoggedIn: Boolean = false,
    val loggedInText: String = "",
    val isConnecting: Boolean = false,
    val infoMessage: String? = null,
    val oauthUrl: String? = null,
    val trackerLoadState: TrackerSettingsLoadState = TrackerSettingsLoadState.Loading,
    val trackerSettings: TrackerSettings = TrackerSettings(),
    val trackerRevision: Long = 0L,
)

class SettingsViewModel(
    application: Application,
    private val authController: CommonInitialAuthController =
        TrackerAppServices.from(application).initialAuthController(),
    private val trackerSettingsRepository: TrackerSettingsRepository =
        TrackerAppServices.from(application).trackerSettingsRepository(),
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        TrackerAppServices.from(application).initialAuthController(),
        TrackerAppServices.from(application).trackerSettingsRepository(),
    )

    private val appContext = application.applicationContext

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            trackerSettingsRepository.observeState().collect { tracker ->
                _state.update { current -> current.withTrackerState(tracker) }
            }
        }
    }

    fun initialize() {
        refreshAuthState()
    }

    fun onHostResumed() {
        refreshAuthState()
    }

    fun onServerUrlChanged(url: String) {
        _state.update { it.copy(serverUrl = url) }
        authController.setServerUrl(url)
    }

    fun connect() {
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

    fun disconnect(mainActivityClass: Class<*>) {
        viewModelScope.launch {
            authController.revokeCurrentSessionTokens()
            AppResetFlow.execute(
                context = appContext,
                reason = AppResetFlow.Reason.MANUAL_SIGN_OUT,
                mainActivityClass = mainActivityClass,
            )
        }
    }

    fun setTrackingProfile(profile: TrackerTrackingProfile) {
        trackerSettingsRepository.setTrackingProfile(profile)
    }

    fun setLoggingIntervalSecFromInput(raw: String) {
        val value = raw.toLongOrNull() ?: return
        trackerSettingsRepository.setLoggingIntervalSec(value)
    }

    fun setDistanceFilterMetersFromInput(raw: String) {
        val value = raw.toFloatOrNull() ?: return
        trackerSettingsRepository.setDistanceFilterMeters(value)
    }

    fun setAccuracyFilterMetersFromInput(raw: String) {
        val value = raw.toFloatOrNull() ?: return
        trackerSettingsRepository.setAccuracyFilterMeters(value)
    }

    fun setLowAccuracyFallbackTimeoutSecFromInput(raw: String) {
        val value = raw.toLongOrNull() ?: return
        trackerSettingsRepository.setLowAccuracyFallbackTimeoutSec(value)
    }

    fun setLowAccuracyFallbackEnabled(enabled: Boolean) {
        trackerSettingsRepository.setLowAccuracyFallbackEnabled(enabled)
    }

    fun setStartOnBoot(enabled: Boolean) {
        trackerSettingsRepository.setStartOnBoot(enabled)
    }

    fun setStartTrackingOnLaunch(enabled: Boolean) {
        trackerSettingsRepository.setStartTrackingOnLaunch(enabled)
    }

    fun setSendExtendedData(enabled: Boolean) {
        trackerSettingsRepository.setSendExtendedData(enabled)
    }

    fun setSignificantDataOnly(enabled: Boolean) {
        trackerSettingsRepository.setSignificantDataOnly(enabled)
    }

    fun setAutoTrackingMode(enabled: Boolean) {
        trackerSettingsRepository.setAutoTrackingMode(enabled)
    }

    fun setKeepScreenOnWhileViewingMap(enabled: Boolean) {
        trackerSettingsRepository.setKeepScreenOnWhileViewingMap(enabled)
    }

    fun clearMessage() {
        _state.update { it.copy(infoMessage = null) }
    }

    private fun refreshAuthState() {
        val server = authController.getConfiguredServerUrlOrPeerDefault()
        val loggedIn = server.isNotBlank() && authController.isLoggedIn()
        val cachedEmail = authController.getCachedUserEmail().orEmpty()
        _state.update {
            it.copy(
                serverUrl = server,
                isLoggedIn = loggedIn,
                loggedInText = if (loggedIn && cachedEmail.isNotBlank()) {
                    "Logged in as $cachedEmail"
                } else {
                    if (loggedIn) "Logged in" else ""
                },
            )
        }
        if (loggedIn && cachedEmail.isBlank()) {
            authController.fetchUserEmail { email ->
                if (!email.isNullOrBlank()) {
                    _state.update { current -> current.copy(loggedInText = "Logged in as $email") }
                }
            }
        }
    }
}
