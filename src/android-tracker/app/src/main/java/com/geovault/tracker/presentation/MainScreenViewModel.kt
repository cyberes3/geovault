package com.geovault.tracker.presentation

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.common.auth.CommonInitialAuthController
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.ui.snackbar.GeoVaultSnackbarModel
import com.geovault.common.update.GeoVaultAndroidReleaseIdentity
import com.geovault.tracker.BuildConfig
import com.geovault.tracker.SelectedTrackerManager
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
import com.geovault.tracker.data.TrackerBootstrapOutcome
import com.geovault.tracker.data.TrackerManagementRepository
import com.geovault.tracker.data.TrackerSessionBootstrap
import com.geovault.tracker.TrackingService
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val updatePrompt: GeoVaultSnackbarModel? = null,
    val updateReleaseUrl: String? = null,
    val mapRecoveryRequestToken: Long = 0L,
    val isPreparingToTrack: Boolean = false,
)

class MainScreenViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val authController: CommonInitialAuthController =
        TrackerAppServices.from(application).initialAuthController()
    private val trackerSettingsRepository: TrackerSettingsRepository =
        TrackerAppServices.from(application).trackerSettingsRepository()
    private val trackerManagementRepository: TrackerManagementRepository =
        TrackerAppServices.from(application).trackerManagementRepository()
    private val sessionBootstrap: TrackerSessionBootstrap =
        TrackerAppServices.from(application).trackerSessionBootstrap()
    private val versionCheckSession = GeoVaultAndroidReleaseIdentity.Tracker.versionCheckSession(
        application = application,
        localFullCommitSha = { BuildConfig.GIT_COMMIT_SHA },
    )

    private val launchBootstrapMutex = Mutex()
    private var activeLaunchBootstrap: Deferred<TrackerBootstrapOutcome>? = null

    private val _state = MutableStateFlow(MainScreenState())
    val state: StateFlow<MainScreenState> = _state.asStateFlow()

    private val _pendingOpenAllTrackersOnMap = MutableStateFlow(false)
    val pendingOpenAllTrackersOnMap: StateFlow<Boolean> = _pendingOpenAllTrackersOnMap.asStateFlow()

    fun requestOpenAllTrackersOnMapFromIntent() {
        _pendingOpenAllTrackersOnMap.value = true
    }

    fun consumePendingOpenAllTrackersOnMap() {
        _pendingOpenAllTrackersOnMap.value = false
    }
    private var startupTrackingAutomationHandled = false
    private var startupTrackingAutomationJob: Job? = null
    private var startupRefreshHandled = false
    private var startupRefreshJob: Job? = null
    private var startupSelectedTrackerGeometryHandled = false
    private var startupSelectedTrackerGeometryJob: Job? = null
    private var serverAccessibilityRefreshJob: Job? = null
    private var preparingStartJob: Job? = null

    init {
        viewModelScope.launch {
            TrackingRuntimeStateStore.state.collect { runtime ->
                if (_state.value.isPreparingToTrack &&
                    StartTrackingPreparationPolicy.shouldClearForRuntime(runtime)
                ) {
                    _state.update { it.copy(isPreparingToTrack = false) }
                }
            }
        }
    }

    fun initialize() {
        refreshAuthState()
        launchPostAuthStartupFlowsIfNeeded()
        if (!_state.value.isAuthenticated) {
            launchVersionCheckIfNeeded()
        }
    }

    fun requestStartTracking() {
        if (preparingStartJob?.isActive == true) return
        preparingStartJob = viewModelScope.launch {
            if (!ensureStartupTrackingPreflight()) return@launch
            _state.update { it.copy(isPreparingToTrack = true, infoMessage = null) }
            if (!ensureSelectedTrackerReadyForStart(showNoSelectionMessage = true)) {
                _state.update { it.copy(isPreparingToTrack = false) }
                return@launch
            }
            if (!_state.value.isPreparingToTrack) return@launch
            val result = TrackingCommandFacade.requestStart(
                getApplication(),
                trigger = RuntimeTrigger.EXPLICIT_START,
                reason = "home_start"
            )
            if (StartTrackingPreparationPolicy.shouldClearAfterStartCommand(result)) {
                _state.update { it.copy(isPreparingToTrack = false) }
            }
        }.also { job ->
            job.invokeOnCompletion { cause ->
                preparingStartJob = null
                if (cause != null) {
                    _state.update { it.copy(isPreparingToTrack = false) }
                }
            }
        }
    }

    fun requestStopTracking() {
        if (_state.value.isPreparingToTrack) {
            preparingStartJob?.cancel()
            preparingStartJob = null
            _state.update { it.copy(isPreparingToTrack = false) }
            return
        }
        TrackingCommandFacade.requestStop(getApplication(), reason = "home_stop")
    }

    fun requestManualPoint() {
        if (!isTrackingServiceActiveOrStarting()) {
            _state.update {
                it.copy(infoMessage = app.getString(R.string.manual_send_point_requires_active_tracking))
            }
            return
        }
        if (TrackingRuntimeStateStore.state.value.selectedTrackerId.isBlank()) {
            _state.update {
                it.copy(infoMessage = app.getString(R.string.no_tracker_selected_go_to_settings))
            }
            return
        }
        app.startService(
            Intent(app, TrackingService::class.java).apply {
                action = TrackingService.ACTION_SEND_MANUAL_POINT
            }
        )
    }

    fun onHostResumed() {
        refreshAuthState()
        if (!_state.value.isAuthenticated) {
            _state.update { it.copy(isConnecting = false, oauthUrl = null) }
        }
        launchPostAuthStartupFlowsIfNeeded()
        if (!_state.value.isAuthenticated) {
            launchVersionCheckIfNeeded()
        }
        refreshServerAccessibilityOnResume()
    }

    fun onAuthServerUrlChanged(url: String) {
        _state.update { it.copy(serverUrl = url) }
        authController.setServerUrl(url)
    }

    fun connectAuth() {
        _state.update { it.copy(isConnecting = true, infoMessage = null) }
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

    fun clearUpdatePrompt() {
        _state.update { it.copy(updatePrompt = null, updateReleaseUrl = null) }
    }

    fun requestMapRecoveryAfterStreamingStop() {
        _state.update { it.copy(mapRecoveryRequestToken = it.mapRecoveryRequestToken + 1L) }
    }

    fun consumeMapRecoveryRequest(token: Long) {
        if (_state.value.mapRecoveryRequestToken == token) {
            _state.update { it.copy(mapRecoveryRequestToken = 0L) }
        }
    }

    private fun refreshAuthState() {
        val wasAuthenticated = _state.value.isAuthenticated
        val server = authController.getConfiguredServerUrlOrPeerDefault()
        val loggedIn = server.isNotBlank() && authController.isLoggedIn()
        _state.update {
            it.copy(
                isAuthenticated = loggedIn,
                serverUrl = server,
            )
        }
        if (wasAuthenticated != loggedIn) {
            resetPostAuthStartupState()
        }
    }

    private fun launchPostAuthStartupFlowsIfNeeded() {
        if (!_state.value.isAuthenticated) return
        launchStartupRefreshIfNeeded()
        launchStartupSelectedTrackerGeometryPreloadIfNeeded()
        launchStartupTrackingAutomationIfNeeded()
        launchVersionCheckIfNeeded()
    }

    /**
     * Pre-fetch the persisted selected tracker's full geometry as part of post-auth
     * launch I/O so the trackers cache (`TrackerManagementStateStore`) already holds
     * server-authoritative geometry by the time the user opens the map. Without this
     * the geometry fetch is gated on the map surface becoming visible, which produces
     * a visible loading spinner / 0,0 flash even when launch I/O finished long ago.
     *
     * Runs in parallel with the rest of the bootstrap fetches; the repository's
     * single-flight gate (`tracker-geometry:<id>`) coalesces against any later fetch
     * triggered by `TrackerMapViewModel`. A failed fetch is logged but does not
     * affect bootstrap success — a subsequent `ExplicitTrackerLoad` reload will retry.
     */
    private fun launchStartupSelectedTrackerGeometryPreloadIfNeeded() {
        if (startupSelectedTrackerGeometryHandled || startupSelectedTrackerGeometryJob?.isActive == true) return
        startupSelectedTrackerGeometryJob = viewModelScope.launch {
            startupSelectedTrackerGeometryHandled = true
            val selectedId = SelectedTrackerPrefs.selectedTrackerId(app).trim()
            if (selectedId.isEmpty()) return@launch
            when (val result = trackerManagementRepository.loadTrackerGeometry(selectedId)) {
                is RepositoryResult.Success -> Unit
                is RepositoryResult.Failure ->
                    Log.w(
                        "MainScreenViewModel",
                        "Selected tracker geometry preload failed trackerId=$selectedId error=${result.error}"
                    )
            }
        }
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
                        if (!isTrackingServiceActiveOrStarting() &&
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
            runAuthenticatedLaunchBootstrap()
        }
    }

    /**
     * Runs user status refresh plus launch-scale bootstrap once; concurrent callers await the same work.
     * Safe to call from [com.geovault.tracker.ui.MainScreen] and from internal startup paths.
     */
    suspend fun runAuthenticatedLaunchBootstrap(): TrackerBootstrapOutcome = coroutineScope {
        lateinit var self: Deferred<TrackerBootstrapOutcome>
        val deferred = launchBootstrapMutex.withLock {
            val existing = activeLaunchBootstrap
            if (existing != null && existing.isActive) {
                return@withLock existing
            }
            val created = async {
                try {
                    val userStatusReachable = refreshUserStatusEndpointReachable()
                    val outcome = sessionBootstrap.runLaunchBootstrap()
                    _state.update { it.copy(isServerAccessible = userStatusReachable) }
                    outcome
                } finally {
                    launchBootstrapMutex.withLock {
                        if (activeLaunchBootstrap === self) {
                            activeLaunchBootstrap = null
                        }
                    }
                }
            }
            self = created
            activeLaunchBootstrap = created
            created
        }
        deferred.await()
    }

    private fun refreshServerAccessibilityOnResume() {
        if (!_state.value.isAuthenticated) return
        if (serverAccessibilityRefreshJob?.isActive == true) return
        serverAccessibilityRefreshJob = viewModelScope.launch {
            startupRefreshJob?.join()
            val userStatusReachable = refreshUserStatusEndpointReachable()
            sessionBootstrap.runResumeBootstrap()
            _state.update { it.copy(isServerAccessible = userStatusReachable) }
        }
    }

    /**
     * Mirrors SPA health against `/api/user/status/` via [GeovaultAuthManager.fetchUserStatusWithResult].
     * HTTP 401 still counts as reachable (host answered); [refreshAuthState] may flip signed-in state afterward.
     */
    private suspend fun refreshUserStatusEndpointReachable(): Boolean =
        suspendCancellableCoroutine { continuation ->
            GeovaultAuthManager.fetchUserStatusWithResult(app) { result ->
                continuation.resume(result.isUserStatusEndpointReachable)
            }
        }

    private suspend fun tryStartTrackingOnLaunch() {
        if (!ensureStartupTrackingPreflight()) return
        if (!ensureSelectedTrackerReadyForStart(showNoSelectionMessage = false)) return
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

    private fun isTrackingServiceActiveOrStarting(): Boolean {
        val runtime = TrackingRuntimeStateStore.state.value
        return runtime.sessionActive || runtime.startupActive
    }

    private fun launchVersionCheckIfNeeded() {
        versionCheckSession.launchIfNeeded(viewModelScope) { prompt, releaseUrl ->
            _state.update { it.copy(updatePrompt = prompt, updateReleaseUrl = releaseUrl) }
        }
    }

    private fun resetPostAuthStartupState() {
        sessionBootstrap.resetForSignedOutSession()
        startupTrackingAutomationHandled = false
        startupRefreshHandled = false
        startupSelectedTrackerGeometryHandled = false
        versionCheckSession.reset()
        startupTrackingAutomationJob?.cancel()
        startupTrackingAutomationJob = null
        startupRefreshJob?.cancel()
        startupRefreshJob = null
        startupSelectedTrackerGeometryJob?.cancel()
        startupSelectedTrackerGeometryJob = null
        serverAccessibilityRefreshJob?.cancel()
        serverAccessibilityRefreshJob = null
        preparingStartJob?.cancel()
        preparingStartJob = null
        _state.update {
            it.copy(
                updatePrompt = null,
                updateReleaseUrl = null,
                mapRecoveryRequestToken = 0L,
                isPreparingToTrack = false
            )
        }
    }

    private suspend fun ensureSelectedTrackerReadyForStart(showNoSelectionMessage: Boolean): Boolean {
        val trackerId = TrackingRuntimeStateStore.state.value.selectedTrackerId.trim()
        if (trackerId.isBlank()) {
            if (showNoSelectionMessage) {
                _state.update {
                    it.copy(infoMessage = app.getString(R.string.no_tracker_selected_go_to_settings))
                }
            }
            return false
        }
        val isValid = when (
            val result = trackerManagementRepository.checkTracker(TrackerCheckRequest(tracker_id = trackerId))
        ) {
            is RepositoryResult.Success -> result.data
            is RepositoryResult.Failure -> false
        }
        if (isValid) return true
        Log.w("MainScreenViewModel", "selected tracker invalid on start, clearing selection")
        SelectedTrackerManager.clearSelectedTrackerAndInvalidateCaches(app)
        trackerManagementRepository.loadTrackers(forceRefresh = true)
        _state.update { it.copy(infoMessage = app.getString(R.string.tracker_validation_failed_go_to_settings)) }
        return false
    }
}
