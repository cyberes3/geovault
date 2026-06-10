package com.geovault.tracker.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.tracker.R
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.location.TrackingPermissionGate
import com.geovault.tracker.settings.TrackerSettingsRepository
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import com.geovault.tracker.services.TrackingRuntimeStateStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val trackerSettingsRepository: TrackerSettingsRepository =
        TrackerAppServices.from(application).trackerSettingsRepository()
    private var lastRuntime: TrackingRuntimeSnapshot = TrackingRuntimeSnapshot()
    private var sparseTrackingEnabled: Boolean = trackerSettingsRepository.getSettings().sparseTracking
    private val permissionFlow = MutableStateFlow(readPermissionSnapshot())

    private val _uiState = MutableStateFlow(
        mergeHomeUiState(
            runtime = lastRuntime,
            permissions = permissionFlow.value,
            statusMessage = "",
        )
    )
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            TrackingRuntimeStateStore.state.collect { snap ->
                lastRuntime = snap
                pushUi()
            }
        }
        viewModelScope.launch {
            trackerSettingsRepository.observeSettings().collect { settings ->
                sparseTrackingEnabled = settings.sparseTracking
                pushUi()
            }
        }
    }

    fun refreshPermissionSnapshot() {
        permissionFlow.value = readPermissionSnapshot()
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
        )
    }

    private fun pushUi() {
        val perms = permissionFlow.value
        val message = buildStatusMessage(lastRuntime)
        _uiState.value = mergeHomeUiState(
            runtime = lastRuntime,
            permissions = perms,
            statusMessage = message,
            sparseTrackingEnabled = sparseTrackingEnabled,
        )
    }

    private fun buildStatusMessage(runtime: TrackingRuntimeSnapshot): String {
        val ctx = getApplication<Application>()
        val parts = mutableListOf<String>()
        if (!runtime.gpsProviderEnabled) {
            parts.add(ctx.getString(R.string.gps_provider_required))
        }
        if (runtime.selectedTrackerId.isBlank()) {
            parts.add(ctx.getString(R.string.no_tracker_selected_go_to_settings))
        }
        if (!runtime.failureReason.isNullOrBlank()) {
            parts.add(runtime.failureReason.trim())
        }
        return parts.distinct().joinToString("\n")
    }
}
