package com.geovault.tracker.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StationaryPauseEligibilityPolicyTest {

    @Test
    fun evaluate_stalePoint_noConfidence_blocks() {
        val decision = StationaryPauseEligibilityPolicy.evaluate(
            stationaryPolicyWantsPause = true,
            localPointFresh = false,
            fallbackPending = false,
            providerAvailable = true,
            sensorFusionHighConfidence = false,
        )

        assertFalse(decision.shouldPause)
        assertEquals(StationaryPauseEligibilityReason.STALE_LOCAL_POINT, decision.reason)
    }

    @Test
    fun evaluate_stalePoint_highConfidence_allows() {
        // Sensor-fusion (IMU/barometer) confidence is GPS-independent:
        // a stale committed trail must not block the pause when the IMU
        // says the device has not moved.
        val decision = StationaryPauseEligibilityPolicy.evaluate(
            stationaryPolicyWantsPause = true,
            localPointFresh = false,
            fallbackPending = false,
            providerAvailable = true,
            sensorFusionHighConfidence = true,
        )

        assertTrue(decision.shouldPause)
        assertEquals(StationaryPauseEligibilityReason.ALLOWED, decision.reason)
    }

    @Test
    fun evaluate_freshPoint_noConfidence_allows() {
        val decision = StationaryPauseEligibilityPolicy.evaluate(
            stationaryPolicyWantsPause = true,
            localPointFresh = true,
            fallbackPending = false,
            providerAvailable = true,
            sensorFusionHighConfidence = false,
        )

        assertTrue(decision.shouldPause)
        assertEquals(StationaryPauseEligibilityReason.ALLOWED, decision.reason)
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
            sensorFusionHighConfidence = false,
        )

        assertTrue(decision.shouldPause)
        assertEquals(StationaryPauseEligibilityReason.ALLOWED, decision.reason)
    }
}
