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
        assertEquals("rejected", decision.metrics?.decision)
        assertEquals("stale", decision.metrics?.reason)
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

    @Test
    fun evaluate_withPreviousPoint_exposesDecisionMetrics() {
        val previous = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = "t",
            lon = 10.0,
            lat = 10.0,
            timestampMs = 1000L,
            accuracyMeters = 5f
        )

        val decision = TrackPointPolicyEngine.evaluate(
            event = TrackPointEvent(
                source = TrackPointSource.LOCAL_GPS,
                trackId = "t",
                lon = 10.0001,
                lat = 10.0001,
                timestampMs = 2000L,
                accuracyMeters = 5f
            ),
            previous = previous,
            history = listOf(previous),
            nowMs = 2000L,
            nowElapsedRealtimeNanos = 0L,
            rawConfig = TrackPointPolicyConfig(
                maxAccuracyMeters = 100f,
                allowDegradedAccuracy = true,
                requireAccuracyForAcceptance = false,
                maxJumpSpeedMps = 60.0,
                maxBurstDistanceMeters = 300.0,
                burstWindowSeconds = 10.0,
                rollingWindowSize = 5,
                outlierPolicy = TrackPointOutlierPolicy.ADJUST,
                normalizeSecondsTimestamps = false
            )
        )

        val metrics = requireNotNull(decision.metrics)
        assertTrue(metrics.rawDistanceMeters > 0.0)
        assertTrue(metrics.effectiveDistanceMeters >= 0.0)
        assertEquals(1.0, metrics.elapsedSeconds, 0.001)
        assertEquals("accepted", metrics.decision)
    }

    @Test
    fun evaluate_firstAcceptedFix_exposesDecisionMetrics() {
        val decision = TrackPointPolicyEngine.evaluate(
            event = TrackPointEvent(
                source = TrackPointSource.LOCAL_GPS,
                trackId = "t",
                lon = 10.0,
                lat = 10.0,
                timestampMs = 2_000L,
                accuracyMeters = 5f
            ),
            previous = null,
            nowMs = 2_000L,
            nowElapsedRealtimeNanos = 0L,
            rawConfig = TrackPointPolicyConfig(
                maxAccuracyMeters = 100f,
                maxJumpSpeedMps = 60.0,
                normalizeSecondsTimestamps = false
            )
        )

        assertTrue(decision.accepted)
        assertEquals("accepted", decision.metrics?.decision)
    }
}
