package com.geovault.tracker.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingSyncPolicyTest {
    @Test
    fun nextRetryDelay_none_returnsBaseDelay() {
        assertEquals(60_000L, TrackingSyncPolicy.nextRetryDelayMs(0, SyncFailureClass.NONE))
    }

    @Test
    fun nextRetryDelay_transient_backsOffExponentially() {
        val delay1 = TrackingSyncPolicy.nextRetryDelayMs(1, SyncFailureClass.TRANSIENT)
        val delay2 = TrackingSyncPolicy.nextRetryDelayMs(2, SyncFailureClass.TRANSIENT)
        val delay3 = TrackingSyncPolicy.nextRetryDelayMs(3, SyncFailureClass.TRANSIENT)
        assertTrue(delay2 > delay1)
        assertTrue(delay3 > delay2)
    }

    @Test
    fun nextRetryDelay_permanent_returnsLongDelay() {
        assertEquals(30L * 60L * 1000L, TrackingSyncPolicy.nextRetryDelayMs(4, SyncFailureClass.PERMANENT))
    }
}
