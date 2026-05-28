package com.geovault.tracker.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StationaryPauseEligibilityPolicyTest {

    @Test
    fun staleLocalPoint_blocksPauseEvenWhenStationaryPolicyWantsPause() {
        val decision = StationaryPauseEligibilityPolicy.evaluate(
            stationaryPolicyWantsPause = true,
            localPointFresh = false,
            fallbackPending = false,
            providerAvailable = true,
        )

        assertFalse(decision.shouldPause)
        assertEquals(StationaryPauseEligibilityReason.STALE_LOCAL_POINT, decision.reason)
    }

    @Test
    fun pendingFallback_blocksPause() {
        val decision = StationaryPauseEligibilityPolicy.evaluate(
            stationaryPolicyWantsPause = true,
            localPointFresh = true,
            fallbackPending = true,
            providerAvailable = true,
        )

        assertFalse(decision.shouldPause)
        assertEquals(StationaryPauseEligibilityReason.FALLBACK_PENDING, decision.reason)
    }

    @Test
    fun freshLocalPoint_withoutFallback_allowsPause() {
        val decision = StationaryPauseEligibilityPolicy.evaluate(
            stationaryPolicyWantsPause = true,
            localPointFresh = true,
            fallbackPending = false,
            providerAvailable = true,
        )

        assertTrue(decision.shouldPause)
        assertEquals(StationaryPauseEligibilityReason.ALLOWED, decision.reason)
    }
}
