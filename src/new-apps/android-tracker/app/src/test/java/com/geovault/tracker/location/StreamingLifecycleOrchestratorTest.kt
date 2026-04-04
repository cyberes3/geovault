package com.geovault.tracker.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingLifecycleOrchestratorTest {

    @Test
    fun connected_resetsAttemptAndMovesToRunning() {
        val previous = StreamingLifecycleState(
            lifecycleState = TrackingLifecycleState.FAILED,
            failureReason = "network",
            reconnectAttempt = 4
        )

        val next = StreamingLifecycleOrchestrator.transition(
            current = previous,
            event = StreamingLifecycleEvent.Connected
        )

        assertEquals(TrackingLifecycleState.RUNNING, next.lifecycleState)
        assertEquals(0, next.reconnectAttempt)
        assertEquals(null, next.failureReason)
    }

    @Test
    fun recoverableFailure_incrementsAttempts_andCapsAtEight() {
        val previous = StreamingLifecycleState(
            lifecycleState = TrackingLifecycleState.FAILED,
            reconnectAttempt = 8
        )
        val next = StreamingLifecycleOrchestrator.transition(
            current = previous,
            event = StreamingLifecycleEvent.RecoverableFailure,
            failureReason = "timeout"
        )

        assertEquals(TrackingLifecycleState.FAILED, next.lifecycleState)
        assertEquals(8, next.reconnectAttempt)
        assertEquals("timeout", next.failureReason)
    }

    @Test
    fun transientReconnectDelay_growsExponentially_untilMax() {
        val attempt1 = StreamingLifecycleOrchestrator.nextReconnectDelayMs(1, StreamingFailureClass.TRANSIENT)
        val attempt2 = StreamingLifecycleOrchestrator.nextReconnectDelayMs(2, StreamingFailureClass.TRANSIENT)
        val attempt8 = StreamingLifecycleOrchestrator.nextReconnectDelayMs(8, StreamingFailureClass.TRANSIENT)

        assertTrue(attempt2 > attempt1)
        assertEquals(60_000L, attempt8)
    }
}
