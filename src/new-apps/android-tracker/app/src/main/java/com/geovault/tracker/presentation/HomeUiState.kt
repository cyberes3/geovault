package com.geovault.tracker.presentation

import com.geovault.tracker.location.TrackingLifecycleState
import com.geovault.tracker.TrackingService
import com.geovault.tracker.services.TrackingRuntimeSnapshot

data class HomePermissionSnapshot(
    val hasForegroundLocation: Boolean = false,
    val hasBackgroundLocation: Boolean = false,
    val hasPostNotifications: Boolean = false,
) {
    val readyForTracking: Boolean
        get() = hasForegroundLocation && hasBackgroundLocation && hasPostNotifications
}

data class HomeUiState(
    val isTracking: Boolean = false,
    val lifecycleState: TrackingLifecycleState = TrackingLifecycleState.STOPPED,
    val selectedTrackerId: String = "",
    val selectedTrackerDisplayName: String = "",
    val queuedPointsVisible: Int = 0,
    val pointsSentThisSession: Int = 0,
    val sessionStartTimeMs: Long = 0L,
    val gpsProviderEnabled: Boolean = true,
    val runtimeFailureReason: String? = null,
    val permissions: HomePermissionSnapshot = HomePermissionSnapshot(),
    val statusMessage: String = "",
)

internal fun mergeHomeUiState(
    runtime: TrackingRuntimeSnapshot,
    permissions: HomePermissionSnapshot,
    statusMessage: String,
): HomeUiState {
    val displayName = runtime.selectedTrackerName.trim().ifBlank {
        runtime.selectedTrackerId.trim()
    }
    val startupInProgress = TrackingService.isStartupInProgress
    val effectiveRunning = runtime.isRunning || startupInProgress
    val effectiveLifecycleState = if (!runtime.isRunning && startupInProgress) {
        TrackingLifecycleState.STARTING
    } else {
        runtime.lifecycleState
    }
    return HomeUiState(
        isTracking = effectiveRunning,
        lifecycleState = effectiveLifecycleState,
        selectedTrackerId = runtime.selectedTrackerId,
        selectedTrackerDisplayName = displayName,
        queuedPointsVisible = runtime.queuedPointsVisible,
        pointsSentThisSession = runtime.pointsSentThisSession,
        sessionStartTimeMs = runtime.sessionStartTimeMs,
        gpsProviderEnabled = runtime.gpsProviderEnabled,
        runtimeFailureReason = runtime.failureReason,
        permissions = permissions,
        statusMessage = statusMessage,
    )
}
