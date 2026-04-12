package com.geovault.tracker.services

import com.geovault.tracker.location.TrackingLifecycleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingSessionCoordinatorTest {

    private val coordinator = TrackingSessionCoordinator()

    @Test
    fun transitionToRunning_resetsSessionFields_andClearsFailure() {
        val previous = TrackingRuntimeSnapshot(
            isRunning = false,
            lifecycleState = TrackingLifecycleState.STOPPED,
            failureReason = "old_failure",
            selectedTrackerId = "tracker-1",
            selectedTrackerName = "Tracker 1",
            sessionStartTimeMs = 111L,
            pointsSentThisSession = 5,
            lastPointSentAtMs = 222L,
            queuedPointsVisible = 7,
            sessionTotalDistanceMeters = 12f,
            lastAccuracyMeters = 5f,
            lastTrackedLatitude = 1.0,
            lastTrackedLongitude = 2.0,
            lastTrackedTimestampMs = 333L,
            lastTrackedPropsJson = """{"manual_send":true}"""
        )

        val next = coordinator.transitionToRunning(
            previous = previous,
            nowMs = 999L,
            sessionVisibleBoundaryId = 444L
        )

        assertTrue(next.isRunning)
        assertEquals(TrackingLifecycleState.RUNNING, next.lifecycleState)
        assertNull(next.failureReason)
        assertEquals(444L, next.sessionVisibleBoundaryId)
        assertEquals(999L, next.sessionStartTimeMs)
        assertEquals(0, next.pointsSentThisSession)
        assertEquals(0L, next.lastPointSentAtMs)
        assertEquals(0, next.queuedPointsVisible)
    }

    @Test
    fun transitionToStopped_keepsFailureReason_andResetsRuntimeMetrics() {
        val previous = TrackingRuntimeSnapshot(
            isRunning = true,
            lifecycleState = TrackingLifecycleState.RUNNING,
            selectedTrackerId = "tracker-1",
            selectedTrackerName = "Tracker 1",
            sessionStartTimeMs = 111L,
            pointsSentThisSession = 5,
            queuedPointsVisible = 7,
            lastTrackedLatitude = 1.0,
            lastTrackedLongitude = 2.0,
            lastTrackedTimestampMs = 333L
        )

        val next = coordinator.transitionToStopped(
            previous = previous,
            failureReason = "fatal_failure"
        )

        assertFalse(next.isRunning)
        assertEquals(TrackingLifecycleState.STOPPED, next.lifecycleState)
        assertEquals("fatal_failure", next.failureReason)
        assertEquals(0L, next.sessionVisibleBoundaryId)
        assertEquals(0L, next.sessionStartTimeMs)
        assertEquals(0, next.pointsSentThisSession)
        assertEquals(0, next.queuedPointsVisible)
        assertNull(next.lastTrackedLatitude)
        assertNull(next.lastTrackedLongitude)
    }
}
