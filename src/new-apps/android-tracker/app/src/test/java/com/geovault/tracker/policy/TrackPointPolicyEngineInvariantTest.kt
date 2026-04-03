package com.geovault.tracker.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackPointPolicyEngineInvariantTest {

    @Test
    fun evaluate_rejectsStaleAgainstFreshnessTtl() {
        val nowMs = 1_000_000L
        val decision = TrackPointPolicyEngine.evaluate(
            event = TrackPointEvent(
                source = TrackPointSource.LOCAL_GPS,
                trackId = "t",
                lon = 10.0,
                lat = 10.0,
                timestampMs = nowMs - 200_000L,
                accuracyMeters = 4f
            ),
            previous = null,
            nowMs = nowMs,
            nowElapsedRealtimeNanos = 0L,
            rawConfig = TrackPointPolicyConfig(
                maxAccuracyMeters = 20f,
                freshnessTtlMs = 120_000L,
                normalizeSecondsTimestamps = false,
                maxJumpSpeedMps = 40.0
            )
        )
        assertFalse(decision.accepted)
        assertEquals(TrackPointRejectReason.STALE, decision.rejectReason)
    }

    @Test
    fun evaluate_uncertaintySuppression_keepsPreviousCoordinate() {
        val previous = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = "t",
            lon = 10.0,
            lat = 10.0,
            timestampMs = 1000L,
            accuracyMeters = 50f
        )
        val decision = TrackPointPolicyEngine.evaluate(
            event = TrackPointEvent(
                source = TrackPointSource.LOCAL_GPS,
                trackId = "t",
                lon = 10.00001,
                lat = 10.00001,
                timestampMs = 2000L,
                accuracyMeters = 50f
            ),
            previous = previous,
            history = listOf(previous),
            nowMs = 2000L,
            nowElapsedRealtimeNanos = 0L,
            rawConfig = TrackPointPolicyConfig(
                maxAccuracyMeters = 100f,
                allowDegradedAccuracy = true,
                requireAccuracyForAcceptance = false,
                maxJumpSpeedMps = 40.0,
                outlierPolicy = TrackPointOutlierPolicy.ADJUST,
                normalizeSecondsTimestamps = false
            )
        )
        assertTrue(decision.accepted)
        assertEquals(TrackPointPolicyEngine.ADJUSTMENT_REASON_UNCERTAINTY_SUPPRESSED, decision.adjustmentReason)
        assertEquals(previous.lat, decision.canonicalEvent?.lat)
        assertEquals(previous.lon, decision.canonicalEvent?.lon)
    }
}
