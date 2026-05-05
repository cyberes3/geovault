package com.geovault.tracker.services

import com.geovault.tracker.location.TrackingLifecycleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSnapshotProjectorTest {

    @Test
    fun project_updatesOperationalFields_andPreservesMetrics() {
        val previous = TrackingRuntimeSnapshot(
            sessionStartTimeMs = 123L,
            pointsSentThisSession = 7,
            queuedPointsVisible = 11,
            sessionTotalDistanceMeters = 42f,
            lastTrackedLatitude = 1.2
        )
        val next = RuntimeSnapshotProjector.project(
            previous = previous,
            input = RuntimeSnapshotProjectionInput(
                isRunning = true,
                recordingRuntime = RecordingRuntime(
                    sessionActive = true,
                    startupActive = false,
                    gpsCollecting = true,
                    pausedForMotion = false,
                    selectedTrackerId = "tracker",
                ),
                lifecycleState = TrackingLifecycleState.RUNNING,
                failureReason = null,
                selectedTrackerId = "tracker",
                selectedTrackerName = "Tracker",
                gpsProviderEnabled = true,
                autoTrackingEnabled = true,
                activeMotionMode = TrackingMotionMode.DRIVING,
                uiStatus = TrackingUiStatus.TRACKING_ACTIVE,
                gpsPaused = false,
                effectiveAccuracyThresholdMeters = 20f,
                sessionVisibleBoundaryId = 55L
            )
        )
        assertTrue(next.isRunning)
        assertTrue(next.sessionActive)
        assertTrue(next.gpsCollecting)
        assertTrue(next.localRecordingActive)
        assertEquals(TrackingLifecycleState.RUNNING, next.lifecycleState)
        assertEquals(123L, next.sessionStartTimeMs)
        assertEquals(7, next.pointsSentThisSession)
        assertEquals(42f, next.sessionTotalDistanceMeters)
        assertEquals(55L, next.sessionVisibleBoundaryId)
        assertEquals(1.2, next.lastTrackedLatitude ?: 0.0, 0.0)
    }
}
