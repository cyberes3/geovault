package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapRuntimeResyncPolicyTest {

    private val policy = TrackerMapRuntimeResyncPolicy()

    @Test
    fun decide_firstObservation_noTransition() {
        val decision = policy.decide(
            previousIsRunning = null,
            currentIsRunning = false,
            mapReady = true,
            mapViewContext = TrackerMapViewContext.SINGLE_TRACKER
        )
        assertEquals(TrackerMapRuntimeTransition.NONE, decision.transition)
        assertFalse(decision.restartTrackPointStream)
        assertFalse(decision.restartDisplayedStreaming)
    }

    @Test
    fun decide_started_singleTrackerAndMapReady_restartsBoth() {
        val decision = policy.decide(
            previousIsRunning = false,
            currentIsRunning = true,
            mapReady = true,
            mapViewContext = TrackerMapViewContext.SINGLE_TRACKER
        )
        assertEquals(TrackerMapRuntimeTransition.STARTED, decision.transition)
        assertTrue(decision.restartTrackPointStream)
        assertTrue(decision.restartDisplayedStreaming)
    }

    @Test
    fun decide_started_groupContext_restartsDisplayedStreamingWhenMapReady() {
        val decision = policy.decide(
            previousIsRunning = false,
            currentIsRunning = true,
            mapReady = true,
            mapViewContext = TrackerMapViewContext.GROUP
        )
        assertEquals(TrackerMapRuntimeTransition.STARTED, decision.transition)
        assertTrue(decision.restartTrackPointStream)
        assertTrue(decision.restartDisplayedStreaming)
    }

    @Test
    fun decide_stopped_noRestarts() {
        val decision = policy.decide(
            previousIsRunning = true,
            currentIsRunning = false,
            mapReady = true,
            mapViewContext = TrackerMapViewContext.SINGLE_TRACKER
        )
        assertEquals(TrackerMapRuntimeTransition.STOPPED, decision.transition)
        assertFalse(decision.restartTrackPointStream)
        assertFalse(decision.restartDisplayedStreaming)
    }
}
