package com.geovault.common.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateDownloadProgressMathTest {

    @Test
    fun `eta returns null when speed too low`() {
        assertNull(UpdateDownloadProgressMath.etaSecondsRemaining(1_000_000L, 512L))
    }

    @Test
    fun `eta scales with remaining bytes and bps`() {
        assertEquals(10L, UpdateDownloadProgressMath.etaSecondsRemaining(10_000_000L, 1_000_000L))
    }

    @Test
    fun `eta zero when nothing remaining`() {
        assertEquals(0L, UpdateDownloadProgressMath.etaSecondsRemaining(0L, 1_000_000L))
    }

    @Test
    fun `ema first sample equals instant`() {
        assertEquals(5000L, UpdateDownloadProgressMath.exponentialMovingAverageBps(null, 5000L, 0.2))
    }

    @Test
    fun `ema smooths toward instant`() {
        val first = UpdateDownloadProgressMath.exponentialMovingAverageBps(null, 1000L, 0.25)
        val second = UpdateDownloadProgressMath.exponentialMovingAverageBps(first, 5000L, 0.25)
        assert(second > first)
    }
}
