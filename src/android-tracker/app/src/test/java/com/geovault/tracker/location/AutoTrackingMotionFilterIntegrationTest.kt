package com.geovault.tracker.location

import com.geovault.common.geo.GeoMath
import com.geovault.tracker.policy.TrackPointDecision
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointPolicyEngine
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.policy.filter.LocationFilterReasons
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

        // Two ~22 m/s cap-exceeded fixes with tight accuracy are enough to
        // promote straight to DRIVING via the strong-confidence path: the
        // first fix fast-emits evidence, the second confirms the streak,
        // and the engine skips WALKING -> BIKING. After promotion the
        // DRIVING profile accepts the next highway-speed fix.
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

        assertFalse(rejectedOne.accepted)
        assertFalse(rejectedTwo.accepted)
        assertEquals(TrackingMotionMode.DRIVING, engine.snapshot().mode)

        val afterPromotion = evaluateAndFeed(
            trackId = trackId,
            lon = -44.9880,
            timestampMs = 60_000L,
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

    @Test
    fun anonymizedCapturedResumeReplay_promotesAndAvoidsRelocationJump() {
        val trackId = "auto-mode-captured-replay"
        TrackPointPolicyEngine.resetStream(TrackPointSource.LOCAL_GPS, trackId)
        val engine = AutoTrackingMotionEngine()
        val coordinator = AutoTrackingMotionCoordinator(
            engine = engine,
            evidenceGate = AutoTrackingMotionEvidenceGate(),
            streakPreserveWindowMs = 10_000L,
        )
        engine.reset(nowMs = 0L)
        var previousAccepted: TrackPointEvent? = null
        val acceptedReasons = mutableListOf<String?>()
        val acceptedDistances = mutableListOf<Double>()

        CAPTURED_RESUME_REPLAY.forEachIndexed { index, fix ->
            var result = evaluate(
                trackId = trackId,
                mode = engine.snapshot().mode,
                lat = fix.lat,
                lon = fix.lon,
                timestampMs = fix.timestampMs,
                accuracyMeters = fix.accuracyMeters,
                speedMps = if (index == 0) 0f else 12f,
            )
            if (!result.accepted) {
                val handling = coordinator.onRejectedOrHeld(
                    metrics = result.metrics,
                    rejectReason = result.rejectReason,
                    eventTimeMs = fix.timestampMs,
                    nowMs = fix.timestampMs,
                )
                if (handling is AutoMotionRejectHandling.Evidence && handling.output.modeChanged) {
                    result = evaluate(
                        trackId = trackId,
                        mode = engine.snapshot().mode,
                        lat = fix.lat,
                        lon = fix.lon,
                        timestampMs = fix.timestampMs,
                        accuracyMeters = fix.accuracyMeters,
                        speedMps = 12f,
                    )
                }
            }
            if (result.accepted) {
                coordinator.clearEvidenceCandidate()
                result.canonicalEvent?.let { event ->
                    previousAccepted?.let { previous ->
                        acceptedDistances += GeoMath.haversineMeters(
                            previous.lat,
                            previous.lon,
                            event.lat,
                            event.lon,
                        )
                    }
                    previousAccepted = event
                }
                acceptedReasons += result.metrics?.reason
                val speedMps = result.metrics?.let { metrics ->
                    if (metrics.elapsedSeconds > 0.0) {
                        (metrics.effectiveDistanceMeters / metrics.elapsedSeconds).toFloat()
                    } else {
                        0f
                    }
                } ?: 0f
                engine.onAcceptedFix(speedMps = speedMps, eventTimeMs = fix.timestampMs)
            }
        }

        assertNotEquals(TrackingMotionMode.WALKING, engine.snapshot().mode)
        assertTrue(LocationFilterReasons.STALE_RELOCATION_CONFIRMED !in acceptedReasons)
        assertTrue(acceptedDistances.maxOrNull().orZero() < 700.0)
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
        lat: Double = 12.0000,
        speedMps: Float = 22f,
    ): TrackPointDecision {
        return TrackPointPolicyEngine.evaluate(
            event = TrackPointEvent(
                source = TrackPointSource.LOCAL_GPS,
                trackId = trackId,
                lat = lat,
                lon = lon,
                timestampMs = timestampMs,
                accuracyMeters = accuracyMeters,
                gpsSpeedMps = speedMps,
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

    private data class CapturedReplayFix(
        val timestampMs: Long,
        val lat: Double,
        val lon: Double,
        val accuracyMeters: Float,
    )

    private fun Double?.orZero(): Double = this ?: 0.0

    private companion object {
        val CAPTURED_RESUME_REPLAY = listOf(
            CapturedReplayFix(timestampMs = 0L, lat = 21.74912668, lon = -30.95408024, accuracyMeters = 8.7f),
            CapturedReplayFix(timestampMs = 20000L, lat = 21.74921339, lon = -30.95402408, accuracyMeters = 8.2f),
            CapturedReplayFix(timestampMs = 40000L, lat = 21.75000937, lon = -30.95565939, accuracyMeters = 7.1f),
            CapturedReplayFix(timestampMs = 60000L, lat = 21.75275742, lon = -30.95563893, accuracyMeters = 7.1f),
            CapturedReplayFix(timestampMs = 80000L, lat = 21.75595713, lon = -30.95559635, accuracyMeters = 7.1f),
            CapturedReplayFix(timestampMs = 100000L, lat = 21.75947196, lon = -30.95551815, accuracyMeters = 7.7f),
            CapturedReplayFix(timestampMs = 120000L, lat = 21.76154187, lon = -30.95620589, accuracyMeters = 7.1f),
            CapturedReplayFix(timestampMs = 140000L, lat = 21.76190300, lon = -30.95927282, accuracyMeters = 6.6f),
            CapturedReplayFix(timestampMs = 160000L, lat = 21.76173997, lon = -30.96121843, accuracyMeters = 8.7f),
            CapturedReplayFix(timestampMs = 180000L, lat = 21.76281328, lon = -30.96251469, accuracyMeters = 8.7f),
            CapturedReplayFix(timestampMs = 200000L, lat = 21.76469258, lon = -30.96405906, accuracyMeters = 8.7f),
            CapturedReplayFix(timestampMs = 220000L, lat = 21.76572008, lon = -30.96643105, accuracyMeters = 7.7f),
            CapturedReplayFix(timestampMs = 239578L, lat = 21.76833686, lon = -30.96603991, accuracyMeters = 48.2f),
            CapturedReplayFix(timestampMs = 260000L, lat = 21.76961548, lon = -30.96774919, accuracyMeters = 9.2f),
            CapturedReplayFix(timestampMs = 280000L, lat = 21.77048984, lon = -30.96939523, accuracyMeters = 9.2f),
            CapturedReplayFix(timestampMs = 300000L, lat = 21.76966116, lon = -30.97160755, accuracyMeters = 9.7f),
            CapturedReplayFix(timestampMs = 320000L, lat = 21.76894803, lon = -30.97381006, accuracyMeters = 9.2f),
            CapturedReplayFix(timestampMs = 340000L, lat = 21.77035334, lon = -30.97423477, accuracyMeters = 10.2f),
            CapturedReplayFix(timestampMs = 360000L, lat = 21.77183732, lon = -30.97426570, accuracyMeters = 10.7f),
            CapturedReplayFix(timestampMs = 380000L, lat = 21.77252610, lon = -30.97561594, accuracyMeters = 7.7f),
            CapturedReplayFix(timestampMs = 400000L, lat = 21.77356378, lon = -30.97597419, accuracyMeters = 7.1f),
            CapturedReplayFix(timestampMs = 420000L, lat = 21.77354052, lon = -30.97612665, accuracyMeters = 12.2f),
            CapturedReplayFix(timestampMs = 440000L, lat = 21.77355955, lon = -30.97611006, accuracyMeters = 10.7f),
            CapturedReplayFix(timestampMs = 461000L, lat = 21.77350033, lon = -30.97588911, accuracyMeters = 28.1f),
            CapturedReplayFix(timestampMs = 480000L, lat = 21.77353838, lon = -30.97569046, accuracyMeters = 8.2f),
            CapturedReplayFix(timestampMs = 520000L, lat = 21.77352426, lon = -30.97607929, accuracyMeters = 8.7f),
        )
    }
}
