package com.geovault.tracker.services

import com.geovault.tracker.location.TrackingLifecycleState

data class RuntimeSnapshotProjectionInput(
    val isRunning: Boolean,
    val lifecycleState: TrackingLifecycleState,
    val failureReason: String?,
    val selectedTrackerId: String,
    val selectedTrackerName: String,
    val gpsProviderEnabled: Boolean,
    val autoTrackingEnabled: Boolean,
    val activeMotionMode: TrackingMotionMode,
    val uiStatus: TrackingUiStatus,
    val gpsPaused: Boolean,
    val effectiveAccuracyThresholdMeters: Float,
    val sessionVisibleBoundaryId: Long,
)

object RuntimeSnapshotProjector {
    @JvmStatic
    fun project(
        previous: TrackingRuntimeSnapshot,
        input: RuntimeSnapshotProjectionInput
    ): TrackingRuntimeSnapshot {
        return previous.copy(
            isRunning = input.isRunning,
            lifecycleState = input.lifecycleState,
            failureReason = input.failureReason,
            selectedTrackerId = input.selectedTrackerId,
            selectedTrackerName = input.selectedTrackerName,
            gpsProviderEnabled = input.gpsProviderEnabled,
            autoTrackingEnabled = input.autoTrackingEnabled,
            activeMotionMode = input.activeMotionMode,
            uiStatus = input.uiStatus,
            gpsPaused = input.gpsPaused,
            effectiveAccuracyThresholdMeters = input.effectiveAccuracyThresholdMeters,
            sessionVisibleBoundaryId = input.sessionVisibleBoundaryId
        )
    }
}
