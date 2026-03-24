package com.geovault.tracker.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TrackPointPipelineTest {
    @Before
    fun resetPipeline() {
        TrackPointPipeline.resetForTests()
    }

    @Test
    fun process_assignsMonotonicOrderingKeys() {
        val first = TrackPointEvent(
            source = TrackPointSource.REMOTE_STREAM,
            trackId = "t1",
            lon = 1.0,
            lat = 2.0,
            timestampMs = 1_000L
        )
        val second = first.copy(timestampMs = 2_000L, lon = 1.1)

        val firstDecision = TrackPointPipeline.process(first, nowMs = 2_500_000L)
        val secondDecision = TrackPointPipeline.process(second, nowMs = 2_500_000L)

        assertTrue(firstDecision.accepted)
        assertTrue(secondDecision.accepted)
        assertTrue((secondDecision.canonicalEvent?.orderingKey ?: 0L) > (firstDecision.canonicalEvent?.orderingKey ?: 0L))
    }

    @Test
    fun processLocalGps_rejectsStalePoint() {
        val nowMs = 1_800_000_000_000L
        val stale = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = "local",
            lon = 1.0,
            lat = 2.0,
            timestampMs = nowMs - 100_000L,
            accuracyMeters = 10f
        )
        val decision = TrackPointPipeline.processLocalGps(
            event = stale,
            maxAccuracyMeters = 50f,
            freshnessTtlMs = 20_000L,
            nowMs = nowMs
        )
        assertFalse(decision.accepted)
        assertEquals(TrackPointRejectReason.STALE, decision.rejectReason)
    }

    @Test
    fun processLocalGps_rejectsPointAboveAccuracyThreshold() {
        val nowMs = 1_800_000_000_000L
        val event = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = "local",
            lon = 1.0,
            lat = 2.0,
            timestampMs = nowMs - 1_000L,
            accuracyMeters = 70f
        )
        val decision = TrackPointPipeline.processLocalGps(
            event = event,
            maxAccuracyMeters = 50f,
            freshnessTtlMs = 120_000L,
            nowMs = nowMs
        )
        assertFalse(decision.accepted)
        assertEquals(TrackPointRejectReason.BAD_ACCURACY, decision.rejectReason)
    }

    @Test
    fun processLocalGps_prefersMonotonicAgeForFreshness() {
        val nowMs = 1_800_000_000_000L
        val nowElapsedNanos = 10_000_000_000L
        val event = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = "local-monotonic-freshness",
            lon = 1.0,
            lat = 2.0,
            timestampMs = nowMs - 5 * 60 * 1000L,
            accuracyMeters = 10f,
            elapsedRealtimeNanos = 9_940_000_000L
        )
        val decision = TrackPointPipeline.processLocalGps(
            event = event,
            maxAccuracyMeters = 50f,
            freshnessTtlMs = 120_000L,
            nowMs = nowMs,
            nowElapsedRealtimeNanos = nowElapsedNanos
        )
        assertTrue(decision.accepted)
    }

    @Test
    fun processLocalGps_rejectsPointWithoutAccuracy() {
        val nowMs = 1_800_000_000_000L
        val event = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = "local",
            lon = 1.0,
            lat = 2.0,
            timestampMs = nowMs - 1_000L,
            accuracyMeters = null
        )
        val decision = TrackPointPipeline.processLocalGps(
            event = event,
            maxAccuracyMeters = 50f,
            freshnessTtlMs = 120_000L,
            nowMs = nowMs
        )
        assertFalse(decision.accepted)
        assertEquals(TrackPointRejectReason.BAD_ACCURACY, decision.rejectReason)
    }

    @Test
    fun process_rejectsOlderPointAcrossSourcesForSameTrack() {
        val nowMs = 1_800_000_000_000L
        val newerLocal = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = "shared-track",
            lon = -104.8,
            lat = 38.9,
            timestampMs = nowMs - 10_000L
        )
        val olderRemote = TrackPointEvent(
            source = TrackPointSource.REMOTE_STREAM,
            trackId = "shared-track",
            lon = -104.7,
            lat = 38.8,
            timestampMs = nowMs - 20_000L
        )

        val accepted = TrackPointPipeline.process(newerLocal, nowMs = nowMs)
        val rejected = TrackPointPipeline.process(olderRemote, nowMs = nowMs)

        assertTrue(accepted.accepted)
        assertFalse(rejected.accepted)
        assertEquals(TrackPointRejectReason.OUT_OF_ORDER, rejected.rejectReason)
    }

    @Test
    fun resetLocalSession_allowsNewSessionPointAfterJump() {
        val trackId = "session-reset"
        val nowMs = 1_800_000_000_000L

        val oldPoint = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = trackId,
            lon = -104.8,
            lat = 38.9,
            timestampMs = nowMs - 5_000L,
            accuracyMeters = 5f
        )
        val oldDecision = TrackPointPipeline.processLocalGps(
            event = oldPoint,
            maxAccuracyMeters = 50f,
            freshnessTtlMs = 120_000L,
            nowMs = nowMs
        )
        assertTrue(oldDecision.accepted)

        val newSessionNowMs = nowMs + 2_000L
        val farAwayPoint = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = trackId,
            lon = -80.0,
            lat = 40.0,
            timestampMs = newSessionNowMs - 1_000L,
            accuracyMeters = 5f
        )

        val withoutReset = TrackPointPipeline.processLocalGps(
            event = farAwayPoint,
            maxAccuracyMeters = 50f,
            freshnessTtlMs = 120_000L,
            nowMs = newSessionNowMs
        )
        assertFalse("Should reject as JUMP without session reset", withoutReset.accepted)
        assertEquals(TrackPointRejectReason.JUMP, withoutReset.rejectReason)

        TrackPointPipeline.resetLocalSession(trackId)

        val afterReset = TrackPointPipeline.processLocalGps(
            event = farAwayPoint,
            maxAccuracyMeters = 50f,
            freshnessTtlMs = 120_000L,
            nowMs = newSessionNowMs
        )
        assertTrue("Should accept after session reset", afterReset.accepted)
    }

    @Test
    fun processLocalGps_longGapLargeDisplacement_reanchorsInsteadOfStickingOnJump() {
        val nowMs = 1_800_000_000_000L
        val trackId = "long-gap-reanchor"
        val baseline = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = trackId,
            lon = -104.8000,
            lat = 38.9000,
            timestampMs = nowMs - (10 * 60 * 1000L),
            accuracyMeters = 8f
        )
        assertTrue(
            TrackPointPipeline.processLocalGps(
                event = baseline,
                maxAccuracyMeters = 50f,
                freshnessTtlMs = 120_000L,
                nowMs = baseline.timestampMs
            ).accepted
        )

        val farAfterGap = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = trackId,
            lon = -104.7200,
            lat = 38.9800,
            timestampMs = nowMs,
            accuracyMeters = 12f
        )
        val decision = TrackPointPipeline.processLocalGps(
            event = farAfterGap,
            maxAccuracyMeters = 50f,
            freshnessTtlMs = 120_000L,
            nowMs = nowMs
        )

        assertTrue(decision.accepted)
    }

    @Test
    fun processLocalGps_jumpRejectStreak_reanchorsWhenAnchorIsStaleButDtStaysShort() {
        val trackId = "jump-streak-reanchor"
        val baselineNowMs = 1_800_000_000_000L
        val baseline = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = trackId,
            lon = -104.8000,
            lat = 38.9000,
            timestampMs = baselineNowMs - 1_000L,
            accuracyMeters = 6f,
            elapsedRealtimeNanos = 1_000_000_000L
        )
        assertTrue(
            TrackPointPipeline.processLocalGps(
                event = baseline,
                maxAccuracyMeters = 50f,
                freshnessTtlMs = 120_000L,
                nowMs = baselineNowMs,
                nowElapsedRealtimeNanos = 1_001_000_000L
            ).accepted
        )

        val stalledNowMs = baselineNowMs + (10 * 60 * 1000L)
        var accepted = false
        for (attempt in 1..6) {
            val farEvent = TrackPointEvent(
                source = TrackPointSource.LOCAL_GPS,
                trackId = trackId,
                lon = -104.7000,
                lat = 39.0000,
                timestampMs = stalledNowMs + attempt,
                accuracyMeters = 8f,
                elapsedRealtimeNanos = 1_001_000_000L + (attempt * 1_000_000_000L)
            )
            val decision = TrackPointPipeline.processLocalGps(
                event = farEvent,
                maxAccuracyMeters = 50f,
                freshnessTtlMs = 120_000L,
                nowMs = stalledNowMs + attempt,
                nowElapsedRealtimeNanos = 1_002_000_000L + (attempt * 1_000_000_000L)
            )
            if (attempt < 6) {
                assertFalse(decision.accepted)
                assertEquals(TrackPointRejectReason.JUMP, decision.rejectReason)
            } else {
                accepted = decision.accepted
            }
        }

        assertTrue("Expected pipeline to recover from jump reject streak", accepted)
    }

    @Test
    fun processLocalGps_mockTimestampSkew_isCanonicalizedToNow() {
        val nowMs = 1_800_000_000_000L
        val staleMockTs = nowMs - (2 * 60 * 60 * 1000L)
        val event = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = "mock-skew",
            lon = -104.8,
            lat = 38.9,
            timestampMs = staleMockTs,
            accuracyMeters = 5f
        )
        val decision = TrackPointPipeline.processLocalGps(
            event = event,
            maxAccuracyMeters = 50f,
            freshnessTtlMs = 120_000L,
            isMockLocation = true,
            nowMs = nowMs
        )
        assertTrue(decision.accepted)
        assertEquals(nowMs, decision.canonicalEvent?.timestampMs)
    }

    @Test
    fun processLocalGps_realTimestampSkew_rejectsAsStale() {
        val nowMs = 1_800_000_000_000L
        val staleRealTs = nowMs - (2 * 60 * 60 * 1000L)
        val event = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = "real-skew",
            lon = -104.8,
            lat = 38.9,
            timestampMs = staleRealTs,
            accuracyMeters = 5f
        )
        val decision = TrackPointPipeline.processLocalGps(
            event = event,
            maxAccuracyMeters = 50f,
            freshnessTtlMs = 120_000L,
            isMockLocation = false,
            nowMs = nowMs
        )
        assertFalse(decision.accepted)
        assertEquals(TrackPointRejectReason.STALE, decision.rejectReason)
    }

    @Test
    fun processLocalGps_mockBypassesJumpFilterButRealDoesNot() {
        val baseNowMs = 1_800_000_000_000L
        val previous = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = "jump-mode",
            lon = -104.8,
            lat = 38.9,
            timestampMs = baseNowMs - 2_000L,
            accuracyMeters = 5f
        )
        val previousDecision = TrackPointPipeline.processLocalGps(
            event = previous,
            maxAccuracyMeters = 50f,
            freshnessTtlMs = 120_000L,
            nowMs = baseNowMs
        )
        assertTrue(previousDecision.accepted)

        val farAway = previous.copy(
            lon = -104.72,
            lat = 38.92,
            timestampMs = baseNowMs - 1_000L
        )
        val realDecision = TrackPointPipeline.processLocalGps(
            event = farAway,
            maxAccuracyMeters = 50f,
            freshnessTtlMs = 120_000L,
            isMockLocation = false,
            nowMs = baseNowMs
        )
        assertFalse(realDecision.accepted)
        assertEquals(TrackPointRejectReason.JUMP, realDecision.rejectReason)

        TrackPointPipeline.resetLocalSession("jump-mode")
        val previousAgain = TrackPointPipeline.processLocalGps(
            event = previous,
            maxAccuracyMeters = 50f,
            freshnessTtlMs = 120_000L,
            nowMs = baseNowMs
        )
        assertTrue(previousAgain.accepted)

        val mockDecision = TrackPointPipeline.processLocalGps(
            event = farAway,
            maxAccuracyMeters = 50f,
            freshnessTtlMs = 120_000L,
            isMockLocation = true,
            nowMs = baseNowMs
        )
        assertTrue(mockDecision.accepted)
    }

    @Test
    fun processLocalGps_capsWalkingScaleSpikeInsteadOfRejecting() {
        val nowMs = 1_800_000_000_000L
        val trackId = "walking-spike"
        val baseline = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = trackId,
            lon = -104.8000,
            lat = 38.9000,
            timestampMs = nowMs - 30_000L,
            accuracyMeters = 8f
        )
        val baselineDecision = TrackPointPipeline.processLocalGps(
            event = baseline,
            maxAccuracyMeters = 50f,
            freshnessTtlMs = 120_000L,
            nowMs = nowMs
        )
        assertTrue(baselineDecision.accepted)

        val spike = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = trackId,
            lon = -104.7992,
            lat = 38.9018,
            timestampMs = nowMs,
            accuracyMeters = 15f
        )
        val spikeDecision = TrackPointPipeline.processLocalGps(
            event = spike,
            maxAccuracyMeters = 50f,
            freshnessTtlMs = 120_000L,
            nowMs = nowMs
        )
        assertTrue(spikeDecision.accepted)
        assertTrue(spikeDecision.adjusted)
        assertEquals("OUTLIER_CAPPED", spikeDecision.adjustmentReason)
    }

    @Test
    fun processLocalGps_shortDeltaLargeTeleport_isRejected() {
        val nowMs = 1_800_000_000_000L
        val trackId = "short-delta-teleport"
        val baseline = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = trackId,
            lon = -104.8000,
            lat = 38.9000,
            timestampMs = nowMs - 1_000L,
            accuracyMeters = 5f
        )
        assertTrue(
            TrackPointPipeline.processLocalGps(
                event = baseline,
                maxAccuracyMeters = 50f,
                freshnessTtlMs = 120_000L,
                nowMs = nowMs
            ).accepted
        )

        val teleport = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = trackId,
            lon = -104.7900,
            lat = 38.9100,
            timestampMs = nowMs - 900L,
            accuracyMeters = 5f
        )
        val teleportDecision = TrackPointPipeline.processLocalGps(
            event = teleport,
            maxAccuracyMeters = 50f,
            freshnessTtlMs = 120_000L,
            nowMs = nowMs
        )
        assertFalse(teleportDecision.accepted)
        assertEquals(TrackPointRejectReason.JUMP, teleportDecision.rejectReason)
    }

    @Test
    fun processWithConfig_adjustPolicy_capsOutlierAndAccepts() {
        val nowMs = 1_800_000_000_000L
        val trackId = "adjust-policy-cap"
        val baseline = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = trackId,
            lon = -104.8000,
            lat = 38.9000,
            timestampMs = nowMs - 5_000L,
            accuracyMeters = 8f
        )
        assertTrue(
            TrackPointPipeline.processWithConfig(
                event = baseline,
                config = TrackPointPolicyConfig(
                    maxAccuracyMeters = 50f,
                    allowDegradedAccuracy = false,
                    requireAccuracyForAcceptance = true,
                    maxFutureSkewMs = 5 * 60 * 1000L,
                    maxJumpSpeedMps = 60.0,
                    maxBurstDistanceMeters = 80.0,
                    burstWindowSeconds = 10.0,
                    rollingWindowSize = 5,
                    outlierPolicy = TrackPointOutlierPolicy.ADJUST,
                    freshnessTtlMs = 120_000L
                ),
                nowMs = nowMs
            ).accepted
        )

        val spike = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = trackId,
            lon = -104.7992,
            lat = 38.9010,
            timestampMs = nowMs,
            accuracyMeters = 10f
        )
        val decision = TrackPointPipeline.processWithConfig(
            event = spike,
            config = TrackPointPolicyConfig(
                maxAccuracyMeters = 50f,
                allowDegradedAccuracy = false,
                requireAccuracyForAcceptance = true,
                maxFutureSkewMs = 5 * 60 * 1000L,
                maxJumpSpeedMps = 60.0,
                maxBurstDistanceMeters = 80.0,
                burstWindowSeconds = 10.0,
                rollingWindowSize = 5,
                outlierPolicy = TrackPointOutlierPolicy.ADJUST,
                freshnessTtlMs = 120_000L
            ),
            nowMs = nowMs
        )
        assertTrue(decision.accepted)
        assertTrue(decision.adjusted)
        assertEquals("OUTLIER_CAPPED", decision.adjustmentReason)
    }

    @Test
    fun processWithConfig_adjustPolicy_stillRejectsExtremeShortGapTeleport() {
        val nowMs = 1_800_000_000_000L
        val trackId = "adjust-policy-hard-reject"
        val baseline = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = trackId,
            lon = -104.8000,
            lat = 38.9000,
            timestampMs = nowMs - 1_000L,
            accuracyMeters = 8f
        )
        assertTrue(
            TrackPointPipeline.processWithConfig(
                event = baseline,
                config = TrackPointPolicyConfig(
                    maxAccuracyMeters = 50f,
                    allowDegradedAccuracy = false,
                    requireAccuracyForAcceptance = true,
                    maxFutureSkewMs = 5 * 60 * 1000L,
                    maxJumpSpeedMps = 60.0,
                    maxBurstDistanceMeters = 80.0,
                    burstWindowSeconds = 10.0,
                    rollingWindowSize = 5,
                    outlierPolicy = TrackPointOutlierPolicy.ADJUST,
                    freshnessTtlMs = 120_000L
                ),
                nowMs = nowMs
            ).accepted
        )

        val teleport = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = trackId,
            lon = -103.5000,
            lat = 40.5000,
            timestampMs = nowMs,
            accuracyMeters = 8f
        )
        val decision = TrackPointPipeline.processWithConfig(
            event = teleport,
            config = TrackPointPolicyConfig(
                maxAccuracyMeters = 50f,
                allowDegradedAccuracy = false,
                requireAccuracyForAcceptance = true,
                maxFutureSkewMs = 5 * 60 * 1000L,
                maxJumpSpeedMps = 60.0,
                maxBurstDistanceMeters = 80.0,
                burstWindowSeconds = 10.0,
                rollingWindowSize = 5,
                outlierPolicy = TrackPointOutlierPolicy.ADJUST,
                freshnessTtlMs = 120_000L
            ),
            nowMs = nowMs
        )
        assertFalse(decision.accepted)
        assertEquals(TrackPointRejectReason.JUMP, decision.rejectReason)
    }

    @Test
    fun processLocalGps_acceptsFastDrivingDisplacementWhenPlausible() {
        val nowMs = 1_800_000_000_000L
        val trackId = "driving-plausible"
        val baseline = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = trackId,
            lon = -104.8000,
            lat = 38.9000,
            timestampMs = nowMs - 10_000L,
            accuracyMeters = 30f
        )
        assertTrue(
            TrackPointPipeline.processLocalGps(
                event = baseline,
                maxAccuracyMeters = 200f,
                freshnessTtlMs = 120_000L,
                nowMs = nowMs
            ).accepted
        )

        val drivingMove = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = trackId,
            lon = -104.7976,
            lat = 38.9010,
            timestampMs = nowMs,
            accuracyMeters = 35f
        )
        val decision = TrackPointPipeline.processLocalGps(
            event = drivingMove,
            maxAccuracyMeters = 200f,
            freshnessTtlMs = 120_000L,
            nowMs = nowMs
        )
        assertTrue(decision.accepted)
    }

    @Test
    fun process_rejectsRemotePointOutsideFreshnessWindow() {
        val nowMs = 1_800_000_000_000L
        val staleRemote = TrackPointEvent(
            source = TrackPointSource.REMOTE_STREAM,
            trackId = "remote-stale",
            lon = -104.8,
            lat = 38.9,
            timestampMs = nowMs - (31L * 60L * 1000L)
        )

        val decision = TrackPointPipeline.process(staleRemote, nowMs = nowMs)

        assertFalse(decision.accepted)
        assertEquals(TrackPointRejectReason.STALE, decision.rejectReason)
    }

    @Test
    fun policyCoercion_clampsUnsafeValuesToGuardrails() {
        val sanitized = TrackPointPolicyCoercion.sanitize(
            TrackPointPolicyConfig(
                maxAccuracyMeters = 100_000f,
                degradedAccuracyMultiplier = 0.2f,
                allowDegradedAccuracy = true,
                requireAccuracyForAcceptance = false,
                maxFutureSkewMs = Long.MAX_VALUE,
                maxJumpSpeedMps = 0.01,
                maxBurstDistanceMeters = 50_000.0,
                burstWindowSeconds = 0.01,
                rollingWindowSize = 1,
                outlierDistanceMultiplier = 0.2,
                accuracyEnvelopePaddingMeters = -50.0,
                accuracyEnvelopeMultiplier = 0.2,
                minimumKinematicCapMeters = -10.0,
                rollingDistanceMultiplier = 50.0,
                freshnessTtlMs = Long.MAX_VALUE
            )
        )
        assertEquals(10_000f, sanitized.maxAccuracyMeters)
        assertEquals(1f, sanitized.degradedAccuracyMultiplier)
        assertEquals(24L * 60L * 60L * 1000L, sanitized.maxFutureSkewMs)
        assertEquals(1.0, sanitized.maxJumpSpeedMps ?: 0.0, 0.0)
        assertEquals(2_000.0, sanitized.maxBurstDistanceMeters, 0.0)
        assertEquals(0.2, sanitized.burstWindowSeconds, 0.0)
        assertEquals(3, sanitized.rollingWindowSize)
        assertEquals(1.0, sanitized.outlierDistanceMultiplier, 0.0)
        assertEquals(0.0, sanitized.accuracyEnvelopePaddingMeters, 0.0)
        assertEquals(1.0, sanitized.accuracyEnvelopeMultiplier, 0.0)
        assertEquals(1.0, sanitized.minimumKinematicCapMeters, 0.0)
        assertEquals(10.0, sanitized.rollingDistanceMultiplier, 0.0)
        assertEquals(24L * 60L * 60L * 1000L, sanitized.freshnessTtlMs)
    }
}
