package com.geovault.tracker.location

import com.geovault.tracker.policy.TrackPointDecision
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointPolicyEngine
import com.geovault.tracker.policy.TrackPointRejectReason
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.policy.filter.LocationFilterConfig
import com.geovault.tracker.policy.filter.MotionProfileTuning
import com.geovault.tracker.services.TrackingMotionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic regression test modeling the mock generator's
 * constant 23 m/s straight-line / 3 m accuracy output. Even with
 * ideal inputs the pre-fix engine dropped three cap-exceeded fixes
 * before promoting to BIKING. With strong-confidence fast-emit and
 * the WALKING -> DRIVING skip, two evidence events are now sufficient
 * and the engine reaches DRIVING within ~16 s of the first
 * cap-exceeded sample. Coordinates (lat=0, lon near -45.0) and
 * timestamps (t0=0) are entirely synthetic and not derived from any
 * captured log.
 */
class AutoTrackingMotionMockReplayTest {

    @Test
    fun mockHighwayLine_reachesDrivingWithinSixteenSeconds() {
        val trackId = "mock-replay"
        TrackPointPolicyEngine.resetStream(TrackPointSource.LOCAL_GPS, trackId)
        val engine = AutoTrackingMotionEngine()
        val evidenceGate = AutoTrackingMotionEvidenceGate()
        engine.reset(nowMs = 0L)

        val seed = TrackPointPolicyEngine.evaluate(
            event = TrackPointEvent(
                source = TrackPointSource.LOCAL_GPS,
                trackId = trackId,
                lat = 0.0,
                lon = -45.0000,
                timestampMs = 0L,
                accuracyMeters = 3f,
                gpsSpeedMps = 0f,
                gpsBearingDeg = 90f,
            ),
            nowMs = 0L,
            config = configFor(engine.snapshot().mode),
        )
        assertTrue(seed.accepted)

        val firstCapExceededTimestampMs = 8_000L
        // 23 m/s eastward at lat 0 -> ~0.000207 lon per second.
        val perSecondLonDelta = 0.000207
        val evidenceEvents = mutableListOf<Long>()

        var ts = firstCapExceededTimestampMs
        while (ts <= firstCapExceededTimestampMs + 16_000L) {
            val lonDelta = perSecondLonDelta * ts / 1000.0
            val result = evaluateAndFeed(
                trackId = trackId,
                lon = -45.0000 + lonDelta,
                timestampMs = ts,
                engine = engine,
                evidenceGate = evidenceGate,
            )
            if (result.metrics?.reason == "speed-cap-exceeded" ||
                result.metrics?.reason == "speed-cap-unconfirmed"
            ) {
                // Genuine cap-exceeded reject; if it produced evidence
                // for the engine we count it.
                evidenceEvents += ts
            }
            if (engine.snapshot().mode == TrackingMotionMode.DRIVING) {
                break
            }
            ts += 8_000L
        }

        assertEquals(
            "expected DRIVING within 16s of first cap-exceeded",
            TrackingMotionMode.DRIVING,
            engine.snapshot().mode,
        )
        assertTrue(
            "should reach DRIVING with at most 2 evidence events; observed $evidenceEvents",
            evidenceEvents.size <= 2,
        )
    }

    private fun evaluateAndFeed(
        trackId: String,
        lon: Double,
        timestampMs: Long,
        engine: AutoTrackingMotionEngine,
        evidenceGate: AutoTrackingMotionEvidenceGate,
    ): TrackPointDecision {
        val result = TrackPointPolicyEngine.evaluate(
            event = TrackPointEvent(
                source = TrackPointSource.LOCAL_GPS,
                trackId = trackId,
                lat = 0.0,
                lon = lon,
                timestampMs = timestampMs,
                accuracyMeters = 3f,
                gpsSpeedMps = 23f,
                gpsBearingDeg = 90f,
            ),
            nowMs = timestampMs,
            config = configFor(engine.snapshot().mode),
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
            } else if (result.rejectReason != TrackPointRejectReason.BAD_ACCURACY &&
                result.rejectReason != TrackPointRejectReason.STALE
            ) {
                engine.onRejectedFix(eventTimeMs = timestampMs)
            }
        }
        return result
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
}
