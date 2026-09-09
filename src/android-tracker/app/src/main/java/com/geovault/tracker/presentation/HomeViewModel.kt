package com.geovault.tracker.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.common.net.GeoVaultApiFailure
import com.geovault.tracker.R
import com.geovault.tracker.TrackerCheckRequest
import com.geovault.tracker.data.CatalogStateStore
import com.geovault.tracker.data.TrackerManagementRepository
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.location.TrackingPermissionGate
import com.geovault.tracker.runtime.RuntimeTrigger
import com.geovault.tracker.runtime.TrackerRuntimeCommands
import com.geovault.tracker.runtime.TrackerRuntimeDocument
import com.geovault.tracker.runtime.TrackerRuntimeEngine
import com.geovault.tracker.runtime.TrackerRuntimeStore
import com.geovault.tracker.settings.TrackerSettingsLoadState
import com.geovault.tracker.settings.TrackerSettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val appServices = TrackerAppServices.from(application)
    private val trackerSettingsRepository: TrackerSettingsRepository =
        appServices.trackerSettingsRepository()
    private val trackerManagementRepository: TrackerManagementRepository =
        appServices.trackerManagementRepository()
    private val catalogStateStore: CatalogStateStore = appServices.catalogStateStore()
    private val selectionController = appServices.catalogSelectionController()
    private var lastDocument: TrackerRuntimeDocument = TrackerRuntimeDocument()
    private var sparseTrackingEnabled: Boolean = trackerSettingsRepository.getSettings().sparseTracking
    private val permissionFlow = MutableStateFlow(readPermissionSnapshot())
    private var isLoggedIn: Boolean = false
    private var startupTrackingAutomationHandled = false
    private var startupTrackingAutomationJob: Job? = null
    private var preparingStartJob: Job? = null
    private var isPreparingToTrack: Boolean = false

    private val _uiState = MutableStateFlow(
        mergeHomeUiState(
            document = lastDocument,
            permissions = permissionFlow.value,
        )
    )
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            TrackerRuntimeStore.state.collect { document ->
                lastDocument = document
                if (isPreparingToTrack && StartTrackingPreparationPolicy.shouldClearForRuntime(document.recording)) {
                    isPreparingToTrack = false
                }
                pushUi()
            }
        }
        viewModelScope.launch {
            trackerSettingsRepository.observeSettings().collect { settings ->
                sparseTrackingEnabled = settings.sparseTracking
                pushUi()
            }
        }
        viewModelScope.launch {
            catalogStateStore.state.collect { pushUi() }
        }
    }

    fun onAccountStateChanged(loggedIn: Boolean) {
        if (isLoggedIn != loggedIn) {
            resetStartAutomation()
        }
        isLoggedIn = loggedIn
        if (isLoggedIn) {
            launchStartupTrackingAutomationIfNeeded()
        }
    }

    fun requestStartTracking() {
        if (preparingStartJob?.isActive == true) return
        preparingStartJob = viewModelScope.launch {
            if (!ensureStartupTrackingPreflight()) return@launch
            isPreparingToTrack = true
            pushUi()
            if (!ensureSelectedTrackerReadyForStart(showNoSelectionMessage = true)) {
                isPreparingToTrack = false
                pushUi()
                return@launch
            }
            if (!isPreparingToTrack) return@launch
            val result = TrackerRuntimeEngine.get(app).handle(
                TrackerRuntimeCommands.Start(
                    trigger = RuntimeTrigger.EXPLICIT_START,
                    reason = "home_start",
                ),
            )
            if (StartTrackingPreparationPolicy.shouldClearAfterStartCommand(result)) {
                isPreparingToTrack = false
                pushUi()
            }
        }.also { job ->
            job.invokeOnCompletion { cause ->
                preparingStartJob = null
                if (cause != null) {
                    isPreparingToTrack = false
                    pushUi()
                }
            }
        }
    }

    fun requestStopTracking() {
        if (isPreparingToTrack) {
            preparingStartJob?.cancel()
            preparingStartJob = null
            isPreparingToTrack = false
            pushUi()
        }
        TrackerRuntimeEngine.get(app).handle(
            TrackerRuntimeCommands.Stop(reason = "home_stop"),
        )
    }

    fun requestManualPoint() {
        if (!TrackerRuntimeStore.value.isRecording) {
            emitHostMessage(app.getString(R.string.manual_send_point_requires_active_tracking))
            return
        }
        if (selectedTrackerId().isBlank()) {
            emitHostMessage(app.getString(R.string.no_tracker_selected_go_to_settings))
            return
        }
        TrackerRuntimeEngine.get(app).handle(TrackerRuntimeCommands.SendManualPoint())
    }

    fun catalogTracker(trackerId: String) = catalogStateStore.tracker(trackerId)

    fun refreshPermissionSnapshot() {
        permissionFlow.value = readPermissionSnapshot()
        pushUi()
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
                        if (!TrackerRuntimeStore.value.isRecording &&
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

    private suspend fun tryStartTrackingOnLaunch() {
        if (!ensureStartupTrackingPreflight()) return
        if (!ensureSelectedTrackerReadyForStart(showNoSelectionMessage = false)) return
        TrackerRuntimeEngine.get(app).handle(
            TrackerRuntimeCommands.Start(
                trigger = RuntimeTrigger.MAIN_START_ON_LAUNCH,
                reason = "main_start_on_launch",
            ),
        )
    }

    private fun ensureStartupTrackingPreflight(): Boolean {
        if (!TrackingPermissionGate.hasLocationPermission(app)) {
            emitHostMessage(app.getString(R.string.location_permission_needed_first))
            return false
        }
        if (!TrackingPermissionGate.hasBackgroundLocationPermission(app)) {
            emitHostMessage(app.getString(R.string.background_location_permission_required))
            return false
        }
        if (!TrackingPermissionGate.hasNotificationPermission(app)) {
            emitHostMessage(app.getString(R.string.notification_permission_required))
            return false
        }
        if (!TrackingPermissionGate.hasBatteryOptimizationExemption(app)) {
            emitHostMessage(app.getString(R.string.battery_optimization_exemption_required))
            return false
        }
        if (!TrackingPermissionGate.hasExactAlarmPermission(app)) {
            emitHostMessage(app.getString(R.string.exact_alarm_permission_required))
            return false
        }
        if (!TrackingPermissionGate.isGpsProviderEnabled(app)) {
            emitHostMessage(app.getString(R.string.gps_provider_required))
            return false
        }
        return true
    }

    private suspend fun ensureSelectedTrackerReadyForStart(showNoSelectionMessage: Boolean): Boolean {
        val trackerId = selectedTrackerId()
        if (trackerId.isBlank()) {
            if (showNoSelectionMessage) {
                emitHostMessage(app.getString(R.string.no_tracker_selected_go_to_settings))
            }
            return false
        }
        val isValid = try {
            trackerManagementRepository.checkTracker(TrackerCheckRequest(tracker_id = trackerId))
        } catch (error: GeoVaultApiFailure) {
            GeoVaultCaptureLog.w(TAG, "selected tracker check failed", error)
            emitHostMessage(error.userMessage())
            return false
        }
        if (isValid) return true
        GeoVaultCaptureLog.w(TAG, "selected tracker invalid on start, clearing selection")
        appServices.clearSelectedTrackerAndInvalidateCaches(app)
        try {
            trackerManagementRepository.loadTrackers(forceRefresh = true)
        } catch (e: GeoVaultApiFailure) {
            GeoVaultCaptureLog.w(TAG, "failed to refresh trackers after invalid selection", e)
        }
        emitHostMessage(app.getString(R.string.tracker_validation_failed_go_to_settings))
        return false
    }

    private fun selectedTrackerId(): String {
        val fromStore = catalogStateStore.state.value.selectedTrackerId.trim()
        if (fromStore.isNotEmpty()) return fromStore
        return selectionController.selectedTrackerId(app)
    }

    private fun resetStartAutomation() {
        startupTrackingAutomationHandled = false
        startupTrackingAutomationJob?.cancel()
        startupTrackingAutomationJob = null
        preparingStartJob?.cancel()
        preparingStartJob = null
        isPreparingToTrack = false
        pushUi()
    }

    private fun readPermissionSnapshot(): HomePermissionSnapshot {
        val ctx = getApplication<Application>()
        return HomePermissionSnapshot(
            hasForegroundLocation = TrackingPermissionGate.hasLocationPermission(ctx),
            hasBackgroundLocation = TrackingPermissionGate.hasBackgroundLocationPermission(ctx),
            hasPostNotifications = TrackingPermissionGate.hasNotificationPermission(ctx),
            hasBatteryOptimizationExemption = TrackingPermissionGate.hasBatteryOptimizationExemption(ctx),
            hasExactAlarmPermission = TrackingPermissionGate.hasExactAlarmPermission(ctx),
            hasActivityRecognition = TrackingPermissionGate.hasActivityRecognitionPermission(ctx),
            hasOtherSensors = TrackingPermissionGate.hasOtherSensorsPermission(ctx),
        )
    }

    private fun pushUi() {
        val selectedId = selectedTrackerId()
        val selectedName = catalogStateStore.tracker(selectedId)?.name
            ?.takeIf { it.isNotBlank() }
            ?: selectionController.selectedTrackerName(app)
        _uiState.value = mergeHomeUiState(
            document = lastDocument,
            permissions = permissionFlow.value,
            sparseTrackingEnabled = sparseTrackingEnabled,
            isPreparingToTrack = isPreparingToTrack,
            selectedTrackerId = selectedId,
            selectedTrackerName = selectedName,
        )
    }

    private fun emitHostMessage(message: String) {
        appServices.uiEffects().emitMessage(message)
    }

    private companion object {
        private const val TAG = "HomeViewModel"
    }
}
