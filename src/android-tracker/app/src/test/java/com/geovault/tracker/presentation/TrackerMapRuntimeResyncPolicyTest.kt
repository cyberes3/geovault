package com.geovault.tracker.presentation

import com.geovault.tracker.map.MapSessionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapRuntimeResyncPolicyTest {

    @Test
    fun decide_firstObservation_noTransition() {
        val decision = MapSessionEngine.decideRuntimeResync(
            previousIsRunning = null,
            currentIsRunning = false,
            mapReady = true,
            isGroup = false,
        )
        assertEquals(TrackerMapRuntimeTransition.NONE, decision.transition)
        assertFalse(decision.restartTrackPointStream)
        assertFalse(decision.restartDisplayedStreaming)
    }

    @Test
    fun decide_started_singleTrackerAndMapReady_restartsBoth() {
        val decision = MapSessionEngine.decideRuntimeResync(
            previousIsRunning = false,
            currentIsRunning = true,
            mapReady = true,
            isGroup = false,
        )
        assertEquals(TrackerMapRuntimeTransition.STARTED, decision.transition)
        assertTrue(decision.restartTrackPointStream)
        assertTrue(decision.restartDisplayedStreaming)
    }

    @Test
    fun decide_started_groupContext_restartsDisplayedStreamingWhenMapReady() {
        val decision = MapSessionEngine.decideRuntimeResync(
            previousIsRunning = false,
            currentIsRunning = true,
            mapReady = true,
            isGroup = true,
        )
        assertEquals(TrackerMapRuntimeTransition.STARTED, decision.transition)
        assertTrue(decision.restartTrackPointStream)
        assertTrue(decision.restartDisplayedStreaming)
    }

    @Test
    fun decide_stopped_noRestarts() {
        val decision = MapSessionEngine.decideRuntimeResync(
            previousIsRunning = true,
            currentIsRunning = false,
            mapReady = true,
            isGroup = false,
        )
        assertEquals(TrackerMapRuntimeTransition.STOPPED, decision.transition)
        assertFalse(decision.restartTrackPointStream)
        assertFalse(decision.restartDisplayedStreaming)
    }
}
