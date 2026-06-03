package com.geovault.tracker.replay

import com.geovault.tracker.location.AutoMotionRejectHandling
import com.geovault.tracker.location.AutoTrackingMotionCoordinator
import com.geovault.tracker.location.AutoTrackingMotionEngine
import com.geovault.tracker.location.AutoTrackingMotionEvidenceGate
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointPolicyEngine
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.policy.filter.LocationFilterConfig
import com.geovault.tracker.policy.filter.MotionProfileTuning
import com.geovault.tracker.services.TrackingMotionMode
import com.geovault.tracker.tracking.TrackingServiceConstants

class CaptureReplayFilterFeed private constructor(
    val engine: AutoTrackingMotionEngine,
    val coordinator: AutoTrackingMotionCoordinator,
) {
    data class ReplayState(
        var motionModeChangedCount: Int = 0,
    )

    fun replay(session: CaptureReplaySession, resetWallMs: Long): ReplayState {
        TrackPointPolicyEngine.resetStream(TrackPointSource.LOCAL_GPS, session.trackId)
        coordinator.reset()
        engine.reset(nowMs = resetWallMs)
        val state = ReplayState()
        CaptureReplayDriver.runWithMotionTicks(session, engine) { frame ->
            feedFrame(session, frame, state)
        }
        return state
    }

    fun feedFrame(session: CaptureReplaySession, frame: CaptureReplayFrame, state: ReplayState) {
        val nowMs = frame.wallNowMs(session)
        TrackPointPolicyEngine.evaluate(
            event = TrackPointEvent(
                source = TrackPointSource.LOCAL_GPS,
                trackId = session.trackId,
                lat = frame.lat,
                lon = frame.lon,
                timestampMs = frame.gpsTimeMs,
                accuracyMeters = frame.accuracy,
                gpsSpeedMps = frame.impliedSpeedMps.coerceAtLeast(0.0).toFloat(),
                gpsBearingDeg = 90f,
            ),
            nowMs = nowMs,
            config = configFor(engine.snapshot().mode),
        )
        if (frame.accepted) {
            coordinator.clearEvidenceCandidate()
            engine.onAcceptedFix(
                speedMps = CaptureReplayMetrics.vettedSpeedMps(frame),
                eventTimeMs = nowMs,
            )
        } else {
            val handling = coordinator.onRejectedOrHeld(
                metrics = CaptureReplayMetrics.toDecisionMetrics(frame),
                rejectReason = CaptureReplayMetrics.toRejectReason(frame),
                eventTimeMs = frame.gpsTimeMs,
                nowMs = nowMs,
            )
            if (handling is AutoMotionRejectHandling.Evidence && handling.output.modeChanged) {
                state.motionModeChangedCount++
            }
        }
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

    companion object {
        fun create(): CaptureReplayFilterFeed {
            val engine = AutoTrackingMotionEngine()
            val coordinator = AutoTrackingMotionCoordinator(
                engine = engine,
                evidenceGate = AutoTrackingMotionEvidenceGate(),
                streakPreserveWindowMs = TrackingServiceConstants.AUTO_MOTION_CAP_EVIDENCE_STREAK_PRESERVE_WINDOW_MS,
            )
            return CaptureReplayFilterFeed(engine, coordinator)
        }
    }
}
