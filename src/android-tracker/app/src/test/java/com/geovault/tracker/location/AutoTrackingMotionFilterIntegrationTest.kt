package com.geovault.tracker.location

import com.geovault.tracker.policy.TrackPointDecision
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointPolicyEngine
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.policy.filter.LocationFilterConfig
import com.geovault.tracker.policy.filter.MotionProfileTuning
import com.geovault.tracker.services.TrackingMotionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTrackingMotionFilterIntegrationTest {

    @Test
    fun walkingFilter_sustainedAccurateSpeedCapRejects_promoteAutoModeAndNextSampleCommits() {
        val trackId = "auto-mode-sustained"
        TrackPointPolicyEngine.resetStream(TrackPointSource.LOCAL_GPS, trackId)
        val engine = AutoTrackingMotionEngine()
        val evidenceGate = AutoTrackingMotionEvidenceGate()
        engine.reset(nowMs = 0L)

        val first = evaluate(trackId = trackId, mode = engine.snapshot().mode, lon = -45.0000, timestampMs = 0L)
        assertTrue(first.accepted)

        val rejectedOne = evaluateAndFeed(
            trackId = trackId,
            lon = -44.9960,
            timestampMs = 20_000L,
            engine = engine,
            evidenceGate = evidenceGate,
        )
        val rejectedTwo = evaluateAndFeed(
            trackId = trackId,
            lon = -44.9920,
            timestampMs = 40_000L,
            engine = engine,
            evidenceGate = evidenceGate,
        )
        val rejectedThree = evaluateAndFeed(
            trackId = trackId,
            lon = -44.9880,
            timestampMs = 60_000L,
            engine = engine,
            evidenceGate = evidenceGate,
        )

        assertFalse(rejectedOne.accepted)
        assertFalse(rejectedTwo.accepted)
        assertFalse(rejectedThree.accepted)
        assertEquals(TrackingMotionMode.BIKING, engine.snapshot().mode)

        val bikingHold = evaluateAndFeed(
            trackId = trackId,
            lon = -44.9840,
            timestampMs = 80_000L,
            engine = engine,
            evidenceGate = evidenceGate,
        )
        assertFalse(bikingHold.accepted)

        val drivingPromotionHold = evaluateAndFeed(
            trackId = trackId,
            lon = -44.9800,
            timestampMs = 100_000L,
            engine = engine,
            evidenceGate = evidenceGate,
        )
        assertFalse(drivingPromotionHold.accepted)

        val recoveryHold = evaluateAndFeed(
            trackId = trackId,
            lon = -44.9759,
            timestampMs = 120_000L,
            engine = engine,
            evidenceGate = evidenceGate,
        )
        assertFalse(recoveryHold.accepted)

        val afterPromotion = evaluateAndFeed(
            trackId = trackId,
            lon = -44.9719,
            timestampMs = 140_000L,
            engine = engine,
            evidenceGate = evidenceGate,
        )

        assertTrue("afterPromotion=$afterPromotion mode=${engine.snapshot().mode}", afterPromotion.accepted)
        assertNotEquals("first-fix", afterPromotion.metrics?.reason)
    }

    @Test
    fun walkingFilter_anonymizedHighwayReplay_promotesOutOfWalking() {
        val trackId = "auto-mode-replay"
        TrackPointPolicyEngine.resetStream(TrackPointSource.LOCAL_GPS, trackId)
        val engine = AutoTrackingMotionEngine()
        val evidenceGate = AutoTrackingMotionEvidenceGate()
        engine.reset(nowMs = 0L)
        val replay = listOf(
            ReplayFix(lon = -45.0000, timestampMs = 0L),
            ReplayFix(lon = -44.9958, timestampMs = 20_000L),
            ReplayFix(lon = -44.9917, timestampMs = 40_000L),
            ReplayFix(lon = -44.9875, timestampMs = 60_000L),
            ReplayFix(lon = -44.9833, timestampMs = 80_000L),
            ReplayFix(lon = -44.9792, timestampMs = 100_000L),
            ReplayFix(lon = -44.9750, timestampMs = 120_000L),
        )

        replay.forEach { fix ->
            evaluateAndFeed(
                trackId = trackId,
                lon = fix.lon,
                timestampMs = fix.timestampMs,
                engine = engine,
                evidenceGate = evidenceGate,
            )
        }

        assertNotEquals(TrackingMotionMode.WALKING, engine.snapshot().mode)
    }

    @Test
    fun walkingFilter_isolatedSpeedCapReject_doesNotPromoteAutoMode() {
        val trackId = "auto-mode-isolated"
        TrackPointPolicyEngine.resetStream(TrackPointSource.LOCAL_GPS, trackId)
        val engine = AutoTrackingMotionEngine()
        val evidenceGate = AutoTrackingMotionEvidenceGate()
        engine.reset(nowMs = 0L)

        assertTrue(evaluate(trackId = trackId, mode = engine.snapshot().mode, lon = -45.0000, timestampMs = 0L).accepted)
        val rejected = evaluateAndFeed(
            trackId = trackId,
            lon = -44.9960,
            timestampMs = 20_000L,
            engine = engine,
            evidenceGate = evidenceGate,
        )

        assertFalse(rejected.accepted)
        assertEquals(TrackingMotionMode.WALKING, engine.snapshot().mode)
    }

    @Test
    fun walkingFilter_lowAccuracyMovement_doesNotPromoteAutoMode() {
        val trackId = "auto-mode-low-accuracy"
        TrackPointPolicyEngine.resetStream(TrackPointSource.LOCAL_GPS, trackId)
        val engine = AutoTrackingMotionEngine()
        val evidenceGate = AutoTrackingMotionEvidenceGate()
        engine.reset(nowMs = 0L)

        assertTrue(evaluate(trackId = trackId, mode = engine.snapshot().mode, lon = -45.0000, timestampMs = 0L).accepted)
        val rejected = evaluateAndFeed(
            trackId = trackId,
            lon = -44.9960,
            timestampMs = 20_000L,
            accuracyMeters = 80f,
            engine = engine,
            evidenceGate = evidenceGate,
        )

        assertFalse(rejected.accepted)
        assertEquals(TrackingMotionMode.WALKING, engine.snapshot().mode)
    }

    private fun evaluateAndFeed(
        trackId: String,
        lon: Double,
        timestampMs: Long,
        accuracyMeters: Float = 8f,
        engine: AutoTrackingMotionEngine,
        evidenceGate: AutoTrackingMotionEvidenceGate,
    ): TrackPointDecision {
        val result = evaluate(
            trackId = trackId,
            mode = engine.snapshot().mode,
            lon = lon,
            timestampMs = timestampMs,
            accuracyMeters = accuracyMeters,
        )
        if (result.accepted) {
            evidenceGate.reset()
            val metrics = result.metrics
            val speedMps = if (metrics != null && metrics.elapsedSeconds > 0.0) {
                (metrics.effectiveDistanceMeters / metrics.elapsedSeconds).toFloat()
            } else {
                0f
            }
            engine.onAcceptedFix(speedMps = speedMps, eventTimeMs = timestampMs)
        } else {
            val evidence = result.metrics?.let { evidenceGate.evaluate(metrics = it, eventTimeMs = timestampMs) }
            if (evidence != null) {
                engine.onMotionEvidence(
                    speedMps = evidence.speedMps,
                    eventTimeMs = timestampMs,
                    confidence = evidence.confidence,
                )
            } else {
                engine.onRejectedFix(eventTimeMs = timestampMs)
            }
        }
        return result
    }

    private fun evaluate(
        trackId: String,
        mode: TrackingMotionMode,
        lon: Double,
        timestampMs: Long,
        accuracyMeters: Float = 8f,
    ): TrackPointDecision {
        return TrackPointPolicyEngine.evaluate(
            event = TrackPointEvent(
                source = TrackPointSource.LOCAL_GPS,
                trackId = trackId,
                lat = 12.0000,
                lon = lon,
                timestampMs = timestampMs,
                accuracyMeters = accuracyMeters,
                gpsSpeedMps = 22f,
                gpsBearingDeg = 90f,
            ),
            nowMs = timestampMs,
            config = configFor(mode),
        )
    }

    private fun configFor(mode: TrackingMotionMode): LocationFilterConfig {
        val tuning = when (mode) {
            TrackingMotionMode.WALKING -> MotionProfileTuning.Walking
            TrackingMotionMode.BIKING -> MotionProfileTuning.Biking
            TrackingMotionMode.DRIVING -> MotionProfileTuning.Driving
        }
        return LocationFilterConfig.fromTuning(
            tuning = tuning,
            trackingAccuracyThresholdMeters = 50.0,
            maxFutureSkewMs = 0L,
            freshnessTtlMs = 0L,
            normalizeSecondsTimestamps = false,
        )
    }

    private data class ReplayFix(
        val lon: Double,
        val timestampMs: Long,
    )
}
