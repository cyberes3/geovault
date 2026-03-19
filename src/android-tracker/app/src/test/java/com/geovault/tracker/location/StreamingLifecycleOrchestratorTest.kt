package com.geovault.tracker.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingLifecycleOrchestratorTest {
    @Test
    fun transition_startConnectedStop_isDeterministic() {
        val starting = StreamingLifecycleOrchestrator.transition(
            current = StreamingLifecycleState(),
            event = StreamingLifecycleEvent.StartRequested
        )
        val running = StreamingLifecycleOrchestrator.transition(
            current = starting,
            event = StreamingLifecycleEvent.Connected
        )
        val stopped = StreamingLifecycleOrchestrator.transition(
            current = running,
            event = StreamingLifecycleEvent.StopRequested
        )
        assertEquals(TrackingLifecycleState.STARTING, starting.lifecycleState)
        assertEquals(TrackingLifecycleState.RUNNING, running.lifecycleState)
        assertEquals(TrackingLifecycleState.STOPPED, stopped.lifecycleState)
    }

    @Test
    fun transition_recoverableFailure_incrementsReconnectAttempt() {
        val failed1 = StreamingLifecycleOrchestrator.transition(
            current = StreamingLifecycleState(),
            event = StreamingLifecycleEvent.RecoverableFailure,
            failureReason = "network down"
        )
        val failed2 = StreamingLifecycleOrchestrator.transition(
            current = failed1,
            event = StreamingLifecycleEvent.RecoverableFailure,
            failureReason = "network down"
        )
        assertEquals(1, failed1.reconnectAttempt)
        assertEquals(2, failed2.reconnectAttempt)
        assertEquals(TrackingLifecycleState.FAILED, failed2.lifecycleState)
    }

    @Test
    fun transition_retryRequested_preservesReconnectAttempt() {
        val failed = StreamingLifecycleOrchestrator.transition(
            current = StreamingLifecycleState(),
            event = StreamingLifecycleEvent.RecoverableFailure,
            failureReason = "network down"
        )
        val retrying = StreamingLifecycleOrchestrator.transition(
            current = failed,
            event = StreamingLifecycleEvent.RetryRequested
        )
        assertEquals(TrackingLifecycleState.STARTING, retrying.lifecycleState)
        assertEquals(1, retrying.reconnectAttempt)
        assertEquals("network down", retrying.failureReason)
    }

    @Test
    fun reconnectDelay_policy_distinguishesTransientAndAuth() {
        val transientDelay = StreamingLifecycleOrchestrator.nextReconnectDelayMs(
            reconnectAttempt = 3,
            failureClass = StreamingFailureClass.TRANSIENT
        )
        val authDelay = StreamingLifecycleOrchestrator.nextReconnectDelayMs(
            reconnectAttempt = 3,
            failureClass = StreamingFailureClass.AUTH
        )
        assertTrue(transientDelay > 0L)
        assertTrue(authDelay > transientDelay)
        assertEquals(
            Long.MAX_VALUE,
            StreamingLifecycleOrchestrator.nextReconnectDelayMs(
                reconnectAttempt = 1,
                failureClass = StreamingFailureClass.PERMANENT
            )
        )
    }
}
