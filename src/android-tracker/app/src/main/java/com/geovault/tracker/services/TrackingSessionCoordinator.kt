package com.geovault.tracker.services

import com.geovault.tracker.location.TrackingLifecycleState

class TrackingSessionCoordinator {
    fun transitionToRunning(
        previous: TrackingRuntimeSnapshot,
        nowMs: Long,
        sessionVisibleBoundaryId: Long
    ): TrackingRuntimeSnapshot {
        return previous.copy(
            isRunning = true,
            recordingRuntime = previous.recordingRuntime.copy(
                sessionActive = true,
                startupActive = false,
                selectedTrackerId = previous.selectedTrackerId,
            ),
            lifecycleState = TrackingLifecycleState.RUNNING,
            failureReason = null,
            sessionVisibleBoundaryId = sessionVisibleBoundaryId,
            sessionStartTimeMs = nowMs,
            pointsSentThisSession = 0,
            lastPointSentAtMs = 0L,
            queuedPointsVisible = 0,
            sessionTotalDistanceMeters = 0f,
            lastAccuracyMeters = null,
            lastTrackedLatitude = null,
            lastTrackedLongitude = null,
            lastTrackedTimestampMs = 0L,
            lastTrackedPropsJson = null
        )
    }

    fun transitionToStopped(previous: TrackingRuntimeSnapshot, failureReason: String?): TrackingRuntimeSnapshot {
        return previous.copy(
            isRunning = false,
            recordingRuntime = RecordingRuntime(
                selectedTrackerId = previous.selectedTrackerId,
            ),
            lifecycleState = TrackingLifecycleState.STOPPED,
            failureReason = failureReason,
            sessionVisibleBoundaryId = 0L,
            sessionStartTimeMs = 0L,
            pointsSentThisSession = 0,
            lastPointSentAtMs = 0L,
            queuedPointsVisible = 0,
            sessionTotalDistanceMeters = 0f,
            lastAccuracyMeters = null,
            lastTrackedLatitude = null,
            lastTrackedLongitude = null,
            lastTrackedTimestampMs = 0L,
            lastTrackedPropsJson = null
        )
    }
}
