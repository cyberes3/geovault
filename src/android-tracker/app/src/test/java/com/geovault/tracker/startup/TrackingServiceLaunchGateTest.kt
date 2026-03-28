package com.geovault.tracker.startup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingServiceLaunchGateTest {
    @Test
    fun computeRetryDelayMs_scalesAndCapsBackoff() {
        assertEquals(2_000L, TrackingServiceLaunchGate.computeRetryDelayMs(1))
        assertEquals(4_000L, TrackingServiceLaunchGate.computeRetryDelayMs(2))
        assertEquals(8_000L, TrackingServiceLaunchGate.computeRetryDelayMs(3))
        assertEquals(16_000L, TrackingServiceLaunchGate.computeRetryDelayMs(4))
        assertEquals(0L, TrackingServiceLaunchGate.computeRetryDelayMs(5))
    }

    @Test
    fun evaluateDispatchEligibility_rejectsUntilBlockedWindowExpires() {
        val decision = TrackingServiceLaunchGate.evaluateDispatchEligibility(
            nowElapsedMs = 1_000L,
            lastAttemptElapsedMs = 500L,
            blockedUntilElapsedMs = 3_000L
        )
        assertFalse(decision.allowed)
        assertEquals("blocked_backoff", decision.reason)
        assertEquals(2_000L, decision.retryInMs)
    }

    @Test
    fun evaluateDispatchEligibility_rejectsMinGapAndAllowsAfterGap() {
        val blockedByGap = TrackingServiceLaunchGate.evaluateDispatchEligibility(
            nowElapsedMs = 1_500L,
            lastAttemptElapsedMs = 1_000L,
            blockedUntilElapsedMs = 0L
        )
        assertFalse(blockedByGap.allowed)
        assertEquals("min_gap", blockedByGap.reason)
        assertTrue(blockedByGap.retryInMs > 0L)

        val allowed = TrackingServiceLaunchGate.evaluateDispatchEligibility(
            nowElapsedMs = 3_000L,
            lastAttemptElapsedMs = 1_000L,
            blockedUntilElapsedMs = 0L
        )
        assertTrue(allowed.allowed)
        assertEquals("allowed", allowed.reason)
    }

    @Test
    fun evaluateDispatchEligibility_ignoresFutureElapsedState_afterReboot() {
        val decision = TrackingServiceLaunchGate.evaluateDispatchEligibility(
            nowElapsedMs = 50_000L,
            lastAttemptElapsedMs = 500_000L,
            blockedUntilElapsedMs = 530_000L
        )
        assertTrue(decision.allowed)
        assertEquals("allowed", decision.reason)
        assertEquals(0L, decision.retryInMs)
    }
}
