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
 * Synthetic regression test inspired by the symptoms observed in the
 * morning real drive: FusedLocationProvider duplicate / stale fixes
 * (BAD_ACCURACY) interleaved with genuine cap-exceeded fixes. Before
 * the streak-preserve fix those stale rejects wiped the pending
 * promotion counter and stretched the transition to DRIVING past 4
 * minutes. The fixture below uses synthetic coordinates and synthetic
 * timestamps (lat=0, lon near -45.0, t0=0) chosen to produce the
 * implied speeds we want to drive through the engine; no captured
 * lat/lon or timestamps are embedded.
 *
 * With the auto-mode driving promotion changes, the engine should
 * reach DRIVING within 60 s of the first cap-exceeded sample.
 */
class AutoTrackingMotionRealDriveReplayTest {

    @Test
    fun morningDrive_reachesDrivingWithinSixtySecondsOfFirstCapExceeded() {
        val trackId = "real-drive-replay"
        TrackPointPolicyEngine.resetStream(TrackPointSource.LOCAL_GPS, trackId)
        val engine = AutoTrackingMotionEngine()
        val evidenceGate = AutoTrackingMotionEvidenceGate()
        engine.reset(nowMs = 0L)

        // Seed the filter with a stationary walking accept at t0.
        val seed = TrackPointPolicyEngine.evaluate(
            event = TrackPointEvent(
                source = TrackPointSource.LOCAL_GPS,
                trackId = trackId,
                lat = 0.0,
                lon = -45.0000,
                timestampMs = 0L,
                accuracyMeters = 8f,
                gpsSpeedMps = 0.5f,
                gpsBearingDeg = 90f,
            ),
            nowMs = 0L,
            config = configFor(engine.snapshot().mode),
        )
        assertTrue(seed.accepted)

        // Each lon delta is sized to produce the implied speed we want
        // the engine to see, at the chosen accuracy. Two BAD_ACCURACY
        // (stale-duplicate) fixes are interleaved between genuine
        // cap-exceeded fixes; before the streak-preserve fix those
        // stale rejects wiped the engine's pending promotion counter
        // on every burst.
        val firstCapExceededTimestampMs = 10_000L
        val replay = listOf(
            // (timestampMs, lonDeltaFromAnchor, accuracyMeters, expectedReject)
            ReplayFix(10_000L, 0.000700, 8f, null), // ~7.8 m/s, tight - fast-emits
            ReplayFix(20_000L, 0.000700, 80f, TrackPointRejectReason.BAD_ACCURACY),
            ReplayFix(30_000L, 0.006000, 8f, null), // ~22 m/s over 30 s - skip path
            ReplayFix(40_000L, 0.006000, 75f, TrackPointRejectReason.BAD_ACCURACY),
        )

        var modeAtFirstDriving: Pair<Long, TrackingMotionMode>? = null
        replay.forEach { fix ->
            evaluateAndFeed(
                trackId = trackId,
                lon = -45.0000 + fix.lonDelta,
                timestampMs = fix.timestampMs,
                accuracyMeters = fix.accuracyMeters,
                engine = engine,
                evidenceGate = evidenceGate,
            )
            if (modeAtFirstDriving == null && engine.snapshot().mode == TrackingMotionMode.DRIVING) {
                modeAtFirstDriving = fix.timestampMs to engine.snapshot().mode
            }
        }

        val drivingTransition = modeAtFirstDriving
        assertTrue(
            "expected DRIVING within 60s of first cap-exceeded; current mode=${engine.snapshot().mode}",
            drivingTransition != null,
        )
        val (drivingTimestamp, _) = drivingTransition!!
        assertTrue(
            "DRIVING reached at $drivingTimestamp ms, first cap-exceeded at $firstCapExceededTimestampMs ms",
            drivingTimestamp - firstCapExceededTimestampMs <= 60_000L,
        )
        assertEquals(TrackingMotionMode.DRIVING, engine.snapshot().mode)
    }

    private data class ReplayFix(
        val timestampMs: Long,
        val lonDelta: Double,
        val accuracyMeters: Float,
        val expectedReject: TrackPointRejectReason?,
    )

    private fun evaluateAndFeed(
        trackId: String,
        lon: Double,
        timestampMs: Long,
        accuracyMeters: Float,
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
                accuracyMeters = accuracyMeters,
                gpsSpeedMps = 22f,
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
            }
            // Note: the production TrackingService streak-preserve fix
            // also skips onRejectedFix for BAD_ACCURACY rejects within a
            // short window of a confirmed cap-exceeded evidence event.
            // The replay applies the same rule: a transient BAD_ACCURACY
            // does not clear a pending promotion streak.
            else if (result.rejectReason != TrackPointRejectReason.BAD_ACCURACY &&
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
