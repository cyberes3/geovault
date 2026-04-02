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
    fun nextRetryDelay_transient_appliesBackoffWithCap() {
        val delay1 = TrackingSyncPolicy.nextRetryDelayMs(1, SyncFailureClass.TRANSIENT)
        val delay4 = TrackingSyncPolicy.nextRetryDelayMs(4, SyncFailureClass.TRANSIENT)
        val delayMax = TrackingSyncPolicy.nextRetryDelayMs(99, SyncFailureClass.TRANSIENT)

        assertEquals(60_000L, delay1)
        assertTrue(delay4 > delay1)
        assertEquals(15L * 60L * 1000L, delayMax)
    }

    @Test
    fun nextRetryDelay_permanent_returnsPermanentDelay() {
        assertEquals(
            30L * 60L * 1000L,
            TrackingSyncPolicy.nextRetryDelayMs(2, SyncFailureClass.PERMANENT)
        )
    }
}
