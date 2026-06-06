package com.geovault.tracker.presentation

import com.geovault.tracker.location.TrackingLifecycleState
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import com.geovault.tracker.services.TrackingStatusAccuracyProjector
import com.geovault.tracker.services.TrackingUiStatus

data class HomePermissionSnapshot(
    val hasForegroundLocation: Boolean = false,
    val hasBackgroundLocation: Boolean = false,
    val hasPostNotifications: Boolean = false,
    val hasBatteryOptimizationExemption: Boolean = false,
    val hasExactAlarmPermission: Boolean = false,
) {
    val readyForTracking: Boolean
        get() = hasForegroundLocation &&
            hasBackgroundLocation &&
            hasPostNotifications
}

data class HomeUiState(
    val isTracking: Boolean = false,
    val lifecycleState: TrackingLifecycleState = TrackingLifecycleState.STOPPED,
    val trackingUiStatus: TrackingUiStatus = TrackingUiStatus.NOT_TRACKING,
    val selectedTrackerId: String = "",
    val selectedTrackerDisplayName: String = "",
    val queuedPointsVisible: Int = 0,
    val pointsSentThisSession: Int = 0,
    val sessionStartTimeMs: Long = 0L,
    val lastPointSentAtMs: Long = 0L,
    val sessionTotalDistanceMeters: Float = 0f,
    val lastAccuracyMeters: Float? = null,
    val effectiveAccuracyThresholdMeters: Float = 0f,
    val lastTrackedLatitude: Double? = null,
    val lastTrackedLongitude: Double? = null,
    val lastTrackedTimestampMs: Long = 0L,
    val gpsProviderEnabled: Boolean = true,
    val runtimeFailureReason: String? = null,
    val permissions: HomePermissionSnapshot = HomePermissionSnapshot(),
    val statusMessage: String = "",
    val sparseTrackingEnabled: Boolean = false,
)

internal fun mergeHomeUiState(
    runtime: TrackingRuntimeSnapshot,
    permissions: HomePermissionSnapshot,
    statusMessage: String,
    sparseTrackingEnabled: Boolean = false,
): HomeUiState {
    val displayName = runtime.selectedTrackerName.trim().ifBlank {
        runtime.selectedTrackerId.trim()
    }
    val effectiveRunning = runtime.sessionActive || runtime.startupActive
    val effectiveLifecycleState = if (!runtime.sessionActive && runtime.startupActive) {
        TrackingLifecycleState.STARTING
    } else {
        runtime.lifecycleState
    }
    val displayAccuracyMeters = TrackingStatusAccuracyProjector.displayAccuracy(
        uiStatus = runtime.uiStatus,
        lastAccuracyMeters = runtime.lastAccuracyMeters,
        currentFixAccuracyMeters = runtime.currentFixAccuracyMeters,
    )
    return HomeUiState(
        isTracking = effectiveRunning,
        lifecycleState = effectiveLifecycleState,
        trackingUiStatus = runtime.uiStatus,
        selectedTrackerId = runtime.selectedTrackerId,
        selectedTrackerDisplayName = displayName,
        queuedPointsVisible = runtime.queuedPointsVisible,
        pointsSentThisSession = runtime.pointsSentThisSession,
        sessionStartTimeMs = runtime.sessionStartTimeMs,
        lastPointSentAtMs = runtime.lastPointSentAtMs,
        sessionTotalDistanceMeters = runtime.sessionTotalDistanceMeters,
        lastAccuracyMeters = displayAccuracyMeters,
        effectiveAccuracyThresholdMeters = runtime.effectiveAccuracyThresholdMeters,
        lastTrackedLatitude = runtime.lastTrackedLatitude,
        lastTrackedLongitude = runtime.lastTrackedLongitude,
        lastTrackedTimestampMs = runtime.lastTrackedTimestampMs,
        gpsProviderEnabled = runtime.gpsProviderEnabled,
        runtimeFailureReason = runtime.failureReason,
        permissions = permissions,
        statusMessage = statusMessage,
        sparseTrackingEnabled = sparseTrackingEnabled,
    )
}
