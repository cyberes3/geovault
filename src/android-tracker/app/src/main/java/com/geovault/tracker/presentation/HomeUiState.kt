package com.geovault.tracker.presentation

import com.geovault.tracker.location.TrackingLifecycleState
import com.geovault.tracker.positioning.TrackingStatusAccuracyProjector
import com.geovault.tracker.positioning.TrackingUiStatus
import com.geovault.tracker.runtime.TrackerRuntimeDocument

data class HomePermissionSnapshot(
    val hasForegroundLocation: Boolean = false,
    val hasBackgroundLocation: Boolean = false,
    val hasPostNotifications: Boolean = false,
    val hasBatteryOptimizationExemption: Boolean = false,
    val hasExactAlarmPermission: Boolean = false,
    val hasActivityRecognition: Boolean = false,
    val hasOtherSensors: Boolean = false,
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
    val sparseTrackingEnabled: Boolean = false,
    val isPreparingToTrack: Boolean = false,
)

internal fun mergeHomeUiState(
    document: TrackerRuntimeDocument,
    permissions: HomePermissionSnapshot,
    sparseTrackingEnabled: Boolean = false,
    isPreparingToTrack: Boolean = false,
    selectedTrackerId: String = "",
    selectedTrackerName: String = "",
): HomeUiState {
    val recording = document.recording
    val displayName = selectedTrackerName.trim().ifBlank {
        selectedTrackerId.trim()
    }
    val effectiveRunning = recording.sessionActive || recording.startupActive
    val effectiveLifecycleState = if (!recording.sessionActive && recording.startupActive) {
        TrackingLifecycleState.STARTING
    } else {
        recording.lifecycleState
    }
    val displayAccuracyMeters = TrackingStatusAccuracyProjector.displayAccuracy(
        uiStatus = recording.uiStatus,
        lastAccuracyMeters = recording.lastAccuracyMeters,
        currentFixAccuracyMeters = recording.currentFixAccuracyMeters,
    )
    return HomeUiState(
        isTracking = effectiveRunning,
        lifecycleState = effectiveLifecycleState,
        trackingUiStatus = recording.uiStatus,
        selectedTrackerId = selectedTrackerId,
        selectedTrackerDisplayName = displayName,
        queuedPointsVisible = recording.queuedPointsVisible,
        pointsSentThisSession = recording.pointsSentThisSession,
        sessionStartTimeMs = recording.sessionStartTimeMs,
        lastPointSentAtMs = recording.lastPointSentAtMs,
        sessionTotalDistanceMeters = recording.sessionTotalDistanceMeters,
        lastAccuracyMeters = displayAccuracyMeters,
        effectiveAccuracyThresholdMeters = recording.effectiveAccuracyThresholdMeters,
        lastTrackedLatitude = recording.lastTrackedLatitude,
        lastTrackedLongitude = recording.lastTrackedLongitude,
        lastTrackedTimestampMs = recording.lastTrackedTimestampMs,
        gpsProviderEnabled = recording.gpsProviderEnabled,
        runtimeFailureReason = recording.failureReason,
        permissions = permissions,
        sparseTrackingEnabled = sparseTrackingEnabled,
        isPreparingToTrack = isPreparingToTrack,
    )
}
