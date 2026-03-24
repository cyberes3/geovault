package com.geovault.tracker.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceRuntimeStateTest {
    @Test
    fun trackingStore_updatesSnapshotFields() {
        TrackingRuntimeStateStore.update {
            it.copy(
                isRunning = true,
                selectedTrackerId = "tracker-id",
                selectedTrackerName = "Tracker Name",
                sessionStartTimeMs = 123L,
                pointsSentThisSession = 4,
                lastPointSentAtMs = 321L,
                queuedPointsVisible = 2
            )
        }

        val state = TrackingRuntimeStateStore.state.value
        assertTrue(state.isRunning)
        assertEquals("tracker-id", state.selectedTrackerId)
        assertEquals("Tracker Name", state.selectedTrackerName)
        assertEquals(123L, state.sessionStartTimeMs)
        assertEquals(4, state.pointsSentThisSession)
        assertEquals(321L, state.lastPointSentAtMs)
        assertEquals(2, state.queuedPointsVisible)
    }

    @Test
    fun liveStreamStore_tracksRunningAndIds() {
        LiveStreamRuntimeStateStore.update {
            it.copy(isRunning = true, activeTrackerIds = setOf("a", "b"))
        }
        var state = LiveStreamRuntimeStateStore.state.value
        assertTrue(state.isRunning)
        assertEquals(setOf("a", "b"), state.activeTrackerIds)

        LiveStreamRuntimeStateStore.update {
            it.copy(isRunning = false, activeTrackerIds = emptySet())
        }
        state = LiveStreamRuntimeStateStore.state.value
        assertFalse(state.isRunning)
        assertTrue(state.activeTrackerIds.isEmpty())
    }
}

