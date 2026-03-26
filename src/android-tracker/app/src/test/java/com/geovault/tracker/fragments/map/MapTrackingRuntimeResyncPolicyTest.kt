package com.geovault.tracker.fragments.map

import org.junit.Assert.assertEquals
import org.junit.Test

class MapTrackingRuntimeResyncPolicyTest {
    private val policy = MapTrackingRuntimeResyncPolicy()

    @Test
    fun decide_startedInSingleContext_requestsResync() {
        val decision = policy.decide(
            previousIsRunning = false,
            currentIsRunning = true,
            mapReady = true,
            mapViewContext = MapViewContext.SINGLE_TRACKER
        )

        assertEquals(MapRuntimeTransition.STARTED, decision.transition)
        assertEquals(true, decision.restartTrackPointStream)
        assertEquals(true, decision.restartDisplayedStreaming)
    }

    @Test
    fun decide_startedInGroupContext_skipsSingleTrackerStreamingRestart() {
        val decision = policy.decide(
            previousIsRunning = false,
            currentIsRunning = true,
            mapReady = true,
            mapViewContext = MapViewContext.GROUP
        )

        assertEquals(MapRuntimeTransition.STARTED, decision.transition)
        assertEquals(true, decision.restartTrackPointStream)
        assertEquals(false, decision.restartDisplayedStreaming)
    }

    @Test
    fun decide_stoppedDoesNotRestartStreams() {
        val decision = policy.decide(
            previousIsRunning = true,
            currentIsRunning = false,
            mapReady = true,
            mapViewContext = MapViewContext.SINGLE_TRACKER
        )

        assertEquals(MapRuntimeTransition.STOPPED, decision.transition)
        assertEquals(false, decision.restartTrackPointStream)
        assertEquals(false, decision.restartDisplayedStreaming)
    }
}
