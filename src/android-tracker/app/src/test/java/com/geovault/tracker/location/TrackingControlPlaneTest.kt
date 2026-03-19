package com.geovault.tracker.location

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingControlPlaneTest {
    @Test
    fun transition_startToRun_setsRunning() {
        val starting = TrackingControlPlane.transition(
            TrackingControlState(),
            TrackingControlEvent.StartRequested
        )
        val running = TrackingControlPlane.transition(
            starting,
            TrackingControlEvent.StartSucceeded
        )
        assertEquals(TrackingLifecycleState.RUNNING, running.lifecycleState)
    }

    @Test
    fun transition_failure_carriesReason() {
        val failed = TrackingControlPlane.transition(
            TrackingControlState(lifecycleState = TrackingLifecycleState.STARTING),
            TrackingControlEvent.StartFailed,
            failureReason = "missing permission"
        )
        assertEquals(TrackingLifecycleState.FAILED, failed.lifecycleState)
        assertEquals("missing permission", failed.failureReason)
    }
}
