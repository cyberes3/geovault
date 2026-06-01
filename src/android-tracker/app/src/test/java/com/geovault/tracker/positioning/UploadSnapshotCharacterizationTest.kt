package com.geovault.tracker.positioning

import com.geovault.tracker.location.SyncFailureClass
import com.geovault.tracker.location.TrackingLifecycleState
import com.geovault.tracker.services.QueueUploadResult
import com.geovault.tracker.services.RecordingRuntime
import com.geovault.tracker.services.RuntimeSnapshotProjectionInput
import com.geovault.tracker.services.RuntimeSnapshotProjector
import com.geovault.tracker.services.TrackingMotionMode
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import com.geovault.tracker.services.TrackingUiStatus
import com.geovault.tracker.services.UploadLivenessState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Documents how upload outcomes and queue counts flow into the runtime snapshot
 * (mirrors [com.geovault.tracker.positioning.UploadSubsystem.applyQueueUploadResult]).
 */
class UploadSnapshotCharacterizationTest {

    @Test
    fun uploadSuccess_updatesLivenessAndSnapshotTimestamps() {
        val uploadedAtMs = 10_000L
        val liveness = UploadLivenessState().onUploadResult(
            result = QueueUploadResult(
                failureClass = SyncFailureClass.NONE,
                rowsDeleted = 2,
                visibleRowsSent = 2,
            ),
            nowMs = uploadedAtMs,
            updateFailureCounters = true,
        )
        assertEquals(SyncFailureClass.NONE, liveness.lastFailureClass)
        assertEquals(uploadedAtMs, liveness.lastSucceededAtMs)

        val previous = TrackingRuntimeSnapshot(
            sessionStartTimeMs = 1_000L,
            pointsSentThisSession = 3,
            queuedPointsVisible = 5,
        )
        val projected = RuntimeSnapshotProjector.project(
            previous = previous,
            input = RuntimeSnapshotProjectionInput(
                isRunning = true,
                recordingRuntime = RecordingRuntime(
                    sessionActive = true,
                    startupActive = false,
                    gpsCollecting = true,
                    pausedForMotion = false,
                    selectedTrackerId = "tracker-1",
                ),
                lifecycleState = TrackingLifecycleState.RUNNING,
                failureReason = null,
                selectedTrackerId = "tracker-1",
                selectedTrackerName = "Tracker",
                gpsProviderEnabled = true,
                autoTrackingEnabled = true,
                activeMotionMode = TrackingMotionMode.WALKING,
                uiStatus = TrackingUiStatus.TRACKING_ACTIVE,
                gpsPaused = false,
                effectiveAccuracyThresholdMeters = 20f,
                sessionVisibleBoundaryId = 10L,
            ),
        ).copy(
            lastPointSentAtMs = uploadedAtMs,
            lastUploadSucceededAtMs = uploadedAtMs,
            queuedPointsVisible = 3,
        )

        assertEquals(uploadedAtMs, projected.lastPointSentAtMs)
        assertEquals(uploadedAtMs, projected.lastUploadSucceededAtMs)
        assertEquals(3, projected.queuedPointsVisible)
        assertTrue(projected.isRunning)
    }

    @Test
    fun uploadFailure_incrementsConsecutiveFailuresInLiveness() {
        val liveness = UploadLivenessState().onUploadResult(
            result = QueueUploadResult(
                failureClass = SyncFailureClass.TRANSIENT,
                failureReason = null,
            ),
            nowMs = 5_000L,
            updateFailureCounters = true,
        )
        assertEquals(SyncFailureClass.TRANSIENT, liveness.lastFailureClass)
        assertEquals(1, liveness.consecutiveFailures)
        assertTrue(liveness.hasUploadTrouble)
    }
}
