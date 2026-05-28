package com.geovault.tracker.services

import com.geovault.tracker.location.SyncFailureClass
import com.geovault.tracker.location.TrackingLifecycleState

data class RuntimeSnapshotProjectionInput(
    val isRunning: Boolean,
    val recordingRuntime: RecordingRuntime,
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
    val providerHealthReason: String = "unknown",
    val uploadLastFailureClass: SyncFailureClass = SyncFailureClass.NONE,
    val uploadConsecutiveFailures: Int = 0,
    val currentSessionQueuedCount: Int = 0,
    val backlogQueuedCount: Int = 0,
)

object RuntimeSnapshotProjector {
    @JvmStatic
    fun project(
        previous: TrackingRuntimeSnapshot,
        input: RuntimeSnapshotProjectionInput
    ): TrackingRuntimeSnapshot {
        return previous.copy(
            isRunning = input.isRunning,
            recordingRuntime = input.recordingRuntime,
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
            sessionVisibleBoundaryId = input.sessionVisibleBoundaryId,
            providerHealthReason = input.providerHealthReason,
            uploadLastFailureClass = input.uploadLastFailureClass,
            uploadConsecutiveFailures = input.uploadConsecutiveFailures,
            currentSessionQueuedCount = input.currentSessionQueuedCount,
            backlogQueuedCount = input.backlogQueuedCount,
        )
    }
}
