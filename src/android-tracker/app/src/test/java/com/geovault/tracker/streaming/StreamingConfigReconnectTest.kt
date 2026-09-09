package com.geovault.tracker.streaming

import com.geovault.tracker.location.StreamingFailureClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingConfigReconnectTest {
    @Test
    fun transientReconnectDelay_growsExponentially_untilMax() {
        val attempt1 = StreamingConfig.nextReconnectDelayMs(1, StreamingFailureClass.TRANSIENT)
        val attempt2 = StreamingConfig.nextReconnectDelayMs(2, StreamingFailureClass.TRANSIENT)
        val attempt8 = StreamingConfig.nextReconnectDelayMs(8, StreamingFailureClass.TRANSIENT)

        assertTrue(attempt2 > attempt1)
        assertEquals(60_000L, attempt8)
    }

    @Test
    fun classifyAuthFailure_escalatesAfterBudget() {
        assertEquals(StreamingFailureClass.AUTH, StreamingConfig.classifyAuthFailure(0))
        assertEquals(StreamingFailureClass.AUTH, StreamingConfig.classifyAuthFailure(2))
        assertEquals(StreamingFailureClass.PERMANENT, StreamingConfig.classifyAuthFailure(3))
    }
}
