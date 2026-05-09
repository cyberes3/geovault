package com.geovault.tracker.policy

import com.geovault.tracker.policy.filter.LocationFilterConfig
import com.geovault.tracker.policy.filter.LocationFilterPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Contract tests for the [TrackPointPolicyEngine] facade. The engine
 * preserves the four "non-filter" gates (invalid coords, future-skew,
 * stale TTL, per-stream out-of-order/duplicate) and routes every other
 * decision to a stream-keyed [com.geovault.tracker.policy.filter.LocationFilter].
 */
class TrackPointPolicyEngineInvariantTest {

    @Before
    fun setUp() {
        TrackPointPolicyEngine.resetAll()
    }

    @After
    fun tearDown() {
        TrackPointPolicyEngine.resetAll()
    }

    @Test
    fun evaluate_rejectsStaleAgainstFreshnessTtl() {
        val nowMs = 1_000_000L
        val decision = TrackPointPolicyEngine.evaluate(
            event = TrackPointEvent(
                source = TrackPointSource.LOCAL_GPS,
                trackId = "stale-track",
                lon = 10.0,
                lat = 10.0,
                timestampMs = nowMs - 200_000L,
                accuracyMeters = 4f,
            ),
            nowMs = nowMs,
            nowElapsedRealtimeNanos = 0L,
            config = LocationFilterConfig.Default.copy(
                freshnessTtlMs = 120_000L,
                normalizeSecondsTimestamps = false,
            ),
        )
        assertFalse(decision.accepted)
        assertEquals(TrackPointRejectReason.STALE, decision.rejectReason)
        assertEquals("rejected", decision.metrics?.decision)
        assertEquals("stale", decision.metrics?.reason)
    }

    @Test
    fun evaluate_rejectsFutureSkew() {
        val nowMs = 1_000_000L
        val decision = TrackPointPolicyEngine.evaluate(
            event = TrackPointEvent(
                source = TrackPointSource.LOCAL_GPS,
                trackId = "skew-track",
                lon = 10.0,
                lat = 10.0,
                timestampMs = nowMs + 5L * 60L * 1000L,
                accuracyMeters = 4f,
            ),
            nowMs = nowMs,
            nowElapsedRealtimeNanos = null,
            config = LocationFilterConfig.Default.copy(
                maxFutureSkewMs = 60_000L,
                normalizeSecondsTimestamps = false,
            ),
        )
        assertFalse(decision.accepted)
        assertEquals(TrackPointRejectReason.TOO_FAR_FUTURE, decision.rejectReason)
    }

    @Test
    fun evaluate_invalidCoordinates_rejected() {
        val decision = TrackPointPolicyEngine.evaluate(
            event = TrackPointEvent(
                source = TrackPointSource.LOCAL_GPS,
                trackId = "bad-coords",
                lon = 999.0,
                lat = 10.0,
                timestampMs = 1_000L,
                accuracyMeters = 4f,
            ),
            nowMs = 1_000L,
            config = LocationFilterConfig.Default.copy(
                normalizeSecondsTimestamps = false,
                freshnessTtlMs = 0L,
            ),
        )
        assertFalse(decision.accepted)
        assertEquals(TrackPointRejectReason.INVALID_COORDINATES, decision.rejectReason)
    }

    @Test
    fun evaluate_uncertaintySuppression_keepsPreviousCoordinate() {
        val track = "uncertainty-track"
        val config = LocationFilterConfig.Default.copy(
            policy = LocationFilterPolicy.Conservative,
            trackingAccuracyThresholdMeters = 200.0,
            normalizeSecondsTimestamps = false,
            freshnessTtlMs = 0L,
        )
        // Three priming fixes at the anchor satisfy the
        // stationary classifier's bufferCount>=3 gate.
        repeat(3) { i ->
            TrackPointPolicyEngine.evaluate(
                event = TrackPointEvent(
                    source = TrackPointSource.LOCAL_GPS,
                    trackId = track,
                    lon = 10.0,
                    lat = 10.0,
                    timestampMs = 1_000L + i * 1_000L,
                    accuracyMeters = 50f,
                ),
                nowMs = 1_000L + i * 1_000L,
                config = config,
            )
        }
        val decision = TrackPointPolicyEngine.evaluate(
            event = TrackPointEvent(
                source = TrackPointSource.LOCAL_GPS,
                trackId = track,
                lon = 10.00001,
                lat = 10.00001,
                timestampMs = 5_000L,
                accuracyMeters = 50f,
            ),
            nowMs = 5_000L,
            config = config,
        )
        assertTrue(decision.accepted)
        assertEquals(TrackPointPolicyEngine.ADJUSTMENT_REASON_UNCERTAINTY_SUPPRESSED, decision.adjustmentReason)
        assertEquals(10.0, decision.canonicalEvent?.lat)
        assertEquals(10.0, decision.canonicalEvent?.lon)
    }

    @Test
    fun evaluate_perStreamOutOfOrder_isRejected() {
        val track = "ooo-track"
        val config = LocationFilterConfig.Default.copy(
            normalizeSecondsTimestamps = false,
            freshnessTtlMs = 0L,
        )
        TrackPointPolicyEngine.evaluate(
            event = TrackPointEvent(
                source = TrackPointSource.LOCAL_GPS,
                trackId = track,
                lon = 10.0,
                lat = 10.0,
                timestampMs = 5_000L,
                accuracyMeters = 5f,
            ),
            nowMs = 5_000L,
            config = config,
        )
        val outOfOrder = TrackPointPolicyEngine.evaluate(
            event = TrackPointEvent(
                source = TrackPointSource.LOCAL_GPS,
                trackId = track,
                lon = 10.0001,
                lat = 10.0001,
                timestampMs = 4_000L,
                accuracyMeters = 5f,
            ),
            nowMs = 5_000L,
            config = config,
        )
        assertFalse(outOfOrder.accepted)
        assertEquals(TrackPointRejectReason.OUT_OF_ORDER, outOfOrder.rejectReason)
    }

    @Test
    fun evaluate_perStreamDuplicate_isRejected() {
        val track = "dup-track"
        val config = LocationFilterConfig.Default.copy(
            normalizeSecondsTimestamps = false,
            freshnessTtlMs = 0L,
        )
        TrackPointPolicyEngine.evaluate(
            event = TrackPointEvent(
                source = TrackPointSource.LOCAL_GPS,
                trackId = track,
                lon = 10.0,
                lat = 10.0,
                timestampMs = 5_000L,
                accuracyMeters = 5f,
            ),
            nowMs = 5_000L,
            config = config,
        )
        val duplicate = TrackPointPolicyEngine.evaluate(
            event = TrackPointEvent(
                source = TrackPointSource.LOCAL_GPS,
                trackId = track,
                lon = 10.0,
                lat = 10.0,
                timestampMs = 5_000L,
                accuracyMeters = 5f,
            ),
            nowMs = 5_000L,
            config = config,
        )
        assertFalse(duplicate.accepted)
        assertEquals(TrackPointRejectReason.DUPLICATE, duplicate.rejectReason)
    }

    @Test
    fun evaluate_acceptedFix_exposesDecisionMetrics() {
        val track = "metrics-track"
        val config = LocationFilterConfig.Default.copy(
            normalizeSecondsTimestamps = false,
            freshnessTtlMs = 0L,
        )
        TrackPointPolicyEngine.evaluate(
            event = TrackPointEvent(
                source = TrackPointSource.LOCAL_GPS,
                trackId = track,
                lon = 10.0,
                lat = 10.0,
                timestampMs = 1_000L,
                accuracyMeters = 5f,
                gpsSpeedMps = 12f,
            ),
            nowMs = 1_000L,
            config = config,
        )
        val decision = TrackPointPolicyEngine.evaluate(
            event = TrackPointEvent(
                source = TrackPointSource.LOCAL_GPS,
                trackId = track,
                lon = 10.0001,
                lat = 10.0001,
                timestampMs = 2_000L,
                accuracyMeters = 5f,
                gpsSpeedMps = 12f,
            ),
            nowMs = 2_000L,
            config = config,
        )
        val metrics = requireNotNull(decision.metrics)
        assertNotNull(decision.canonicalEvent)
        assertTrue(metrics.rawDistanceMeters > 0.0)
        assertTrue(metrics.elapsedSeconds > 0.0)
        assertEquals("accepted", metrics.decision)
    }

    @Test
    fun evaluate_resetStream_recreatesFreshFilter() {
        val track = "reset-track"
        val config = LocationFilterConfig.Default.copy(
            normalizeSecondsTimestamps = false,
            freshnessTtlMs = 0L,
        )
        TrackPointPolicyEngine.evaluate(
            event = TrackPointEvent(
                source = TrackPointSource.LOCAL_GPS,
                trackId = track,
                lon = 10.0,
                lat = 10.0,
                timestampMs = 5_000L,
                accuracyMeters = 5f,
            ),
            nowMs = 5_000L,
            config = config,
        )
        TrackPointPolicyEngine.resetStream(TrackPointSource.LOCAL_GPS, track)
        val acceptedAfterReset = TrackPointPolicyEngine.evaluate(
            event = TrackPointEvent(
                source = TrackPointSource.LOCAL_GPS,
                trackId = track,
                lon = 10.0,
                lat = 10.0,
                timestampMs = 4_000L,
                accuracyMeters = 5f,
            ),
            nowMs = 5_000L,
            config = config,
        )
        assertTrue("after reset, an earlier-ts fix is no longer 'out of order' against an anchor", acceptedAfterReset.accepted)
    }
}
