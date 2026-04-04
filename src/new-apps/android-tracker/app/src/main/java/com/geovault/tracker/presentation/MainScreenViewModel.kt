package com.geovault.tracker.presentation

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.common.auth.CommonInitialAuthController
import com.geovault.common.GeovaultAuthManager
import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.TrackerCheckRequest
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.location.TrackingPermissionGate
import com.geovault.tracker.runtime.RuntimeTrigger
import com.geovault.tracker.TrackingCommandFacade
import com.geovault.tracker.R
import com.geovault.tracker.services.TrackingRuntimeStateStore
import com.geovault.tracker.settings.TrackerSettingsLoadState
import com.geovault.tracker.settings.TrackerSettingsRepository
import com.geovault.tracker.data.GroupManagementRepository
import com.geovault.tracker.data.TrackerManagementRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class MainScreenState(
    val isAuthenticated: Boolean = false,
    val serverUrl: String = "",
    val isServerAccessible: Boolean = true,
    val isConnecting: Boolean = false,
    val oauthUrl: String? = null,
    val infoMessage: String? = null,
)

class MainScreenViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val authController: CommonInitialAuthController =
        TrackerAppServices.from(application).initialAuthController()
    private val trackerSettingsRepository: TrackerSettingsRepository =
        TrackerAppServices.from(application).trackerSettingsRepository()
    private val trackerManagementRepository: TrackerManagementRepository =
        TrackerAppServices.from(application).trackerManagementRepository()
    private val groupManagementRepository: GroupManagementRepository =
        TrackerAppServices.from(application).groupManagementRepository()

    private val _state = MutableStateFlow(MainScreenState())
    val state: StateFlow<MainScreenState> = _state.asStateFlow()
    private var startupTrackingAutomationHandled = false
    private var startupTrackingAutomationJob: Job? = null
    private var startupRefreshHandled = false
    private var startupRefreshJob: Job? = null

    fun initialize() {
        refreshAuthState()
        launchPostAuthStartupFlowsIfNeeded()
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
        launchPostAuthStartupFlowsIfNeeded()
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

    private fun launchPostAuthStartupFlowsIfNeeded() {
        if (!_state.value.isAuthenticated) return
        launchStartupRefreshIfNeeded()
        launchStartupTrackingAutomationIfNeeded()
    }

    private fun launchStartupTrackingAutomationIfNeeded() {
        if (startupTrackingAutomationHandled || startupTrackingAutomationJob?.isActive == true) return
        startupTrackingAutomationJob = viewModelScope.launch {
            trackerSettingsRepository.observeState().collect { settingsState ->
                when (settingsState.loadState) {
                    TrackerSettingsLoadState.Loading -> Unit
                    TrackerSettingsLoadState.Error -> {
                        startupTrackingAutomationHandled = true
                        this.cancel()
                    }
                    TrackerSettingsLoadState.Ready -> {
                        if (settingsState.wasTrackingBeforeExit) {
                            trackerSettingsRepository.clearWasTrackingBeforeExit()
                        }
                        if (!TrackingRuntimeStateStore.state.value.isRunning &&
                            settingsState.settings.startTrackingOnLaunch
                        ) {
                            tryStartTrackingOnLaunch()
                        }
                        startupTrackingAutomationHandled = true
                        this.cancel()
                    }
                }
            }
        }
    }

    private fun launchStartupRefreshIfNeeded() {
        if (startupRefreshHandled || startupRefreshJob?.isActive == true) return
        startupRefreshJob = viewModelScope.launch {
            startupRefreshHandled = true
            refreshUserStatus()
            coroutineScope {
                val trackersDef = async {
                    trackerManagementRepository.loadTrackers(forceRefresh = true)
                }
                launch { groupManagementRepository.loadGroups(forceRefresh = true) }
                launch { trackerManagementRepository.loadMapVisibility(forceRefresh = true) }
                launch { trackerManagementRepository.loadAvailableToAdd(forceRefresh = true) }
                val trackersResult = trackersDef.await()
                _state.update {
                    it.copy(isServerAccessible = trackersResult is RepositoryResult.Success)
                }
            }
            val selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(app)
            if (selectedTrackerId.isNotBlank()) {
                coroutineScope {
                    launch { trackerManagementRepository.loadTracker(selectedTrackerId) }
                    launch { trackerManagementRepository.loadTrackerGeometry(selectedTrackerId) }
                }
            }
        }
    }

    private suspend fun refreshUserStatus() {
        suspendCancellableCoroutine<Unit> { continuation ->
            GeovaultAuthManager.fetchUserStatus(app) {
                continuation.resume(Unit)
            }
        }
    }

    private suspend fun tryStartTrackingOnLaunch() {
        if (!ensureStartupTrackingPreflight()) return
        val trackerId = SelectedTrackerPrefs.selectedTrackerId(app)
        if (trackerId.isBlank()) return
        val isValid = when (
            val result = trackerManagementRepository.checkTracker(TrackerCheckRequest(tracker_id = trackerId))
        ) {
            is RepositoryResult.Success -> result.data
            is RepositoryResult.Failure -> false
        }
        if (!isValid) {
            Log.w("MainScreenViewModel", "startup auto-start invalid selected tracker, clearing selection")
            SelectedTrackerPrefs.clearSelectedTracker(app)
            trackerManagementRepository.clearSelectedTrackerCaches()
            trackerManagementRepository.loadTrackers(forceRefresh = true)
            TrackingRuntimeStateStore.update {
                it.copy(
                    selectedTrackerId = "",
                    selectedTrackerName = "",
                )
            }
            _state.update { it.copy(infoMessage = app.getString(R.string.tracker_validation_failed_go_to_settings)) }
            return
        }
        TrackingCommandFacade.requestStart(
            context = app,
            trigger = RuntimeTrigger.MAIN_START_ON_LAUNCH,
            reason = "main_start_on_launch"
        )
    }

    private fun ensureStartupTrackingPreflight(): Boolean {
        if (!TrackingPermissionGate.hasLocationPermission(app)) {
            _state.update { it.copy(infoMessage = app.getString(R.string.location_permission_needed_first)) }
            return false
        }
        if (!TrackingPermissionGate.hasBackgroundLocationPermission(app)) {
            _state.update { it.copy(infoMessage = app.getString(R.string.background_location_permission_required)) }
            return false
        }
        if (!TrackingPermissionGate.hasNotificationPermission(app)) {
            _state.update { it.copy(infoMessage = app.getString(R.string.notification_permission_required)) }
            return false
        }
        if (!TrackingPermissionGate.hasBatteryOptimizationExemption(app)) {
            _state.update { it.copy(infoMessage = app.getString(R.string.battery_optimization_exemption_required)) }
            return false
        }
        if (!TrackingPermissionGate.hasExactAlarmPermission(app)) {
            _state.update { it.copy(infoMessage = app.getString(R.string.exact_alarm_permission_required)) }
            return false
        }
        if (!TrackingPermissionGate.isGpsProviderEnabled(app)) {
            _state.update { it.copy(infoMessage = app.getString(R.string.gps_provider_required)) }
            return false
        }
        return true
    }
}
