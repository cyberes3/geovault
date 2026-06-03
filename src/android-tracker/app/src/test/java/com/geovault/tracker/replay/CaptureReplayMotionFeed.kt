package com.geovault.tracker.replay

import com.geovault.tracker.location.AutoTrackingMotionCoordinator
import com.geovault.tracker.location.AutoTrackingMotionEngine
import com.geovault.tracker.location.AutoTrackingMotionEvidenceGate
import com.geovault.tracker.tracking.TrackingServiceConstants

class CaptureReplayMotionFeed private constructor(
    val engine: AutoTrackingMotionEngine,
    val coordinator: AutoTrackingMotionCoordinator,
) {
    fun replay(session: CaptureReplaySession, resetWallMs: Long) {
        coordinator.reset()
        engine.reset(nowMs = resetWallMs)
        CaptureReplayDriver.runWithMotionTicks(session, engine) { frame ->
            feedFrame(frame, session)
        }
    }

    fun feedFrame(frame: CaptureReplayFrame, session: CaptureReplaySession) {
        val nowMs = frame.wallNowMs(session)
        val metrics = CaptureReplayMetrics.toDecisionMetrics(frame)
        if (frame.accepted) {
            coordinator.clearEvidenceCandidate()
            engine.onAcceptedFix(
                speedMps = CaptureReplayMetrics.vettedSpeedMps(frame),
                eventTimeMs = nowMs,
            )
            return
        }
        coordinator.onRejectedOrHeld(
            metrics = metrics,
            rejectReason = CaptureReplayMetrics.toRejectReason(frame),
            eventTimeMs = frame.gpsTimeMs,
            nowMs = nowMs,
        )
    }

    companion object {
        fun create(): CaptureReplayMotionFeed {
            val engine = AutoTrackingMotionEngine()
            val coordinator = AutoTrackingMotionCoordinator(
                engine = engine,
                evidenceGate = AutoTrackingMotionEvidenceGate(),
                streakPreserveWindowMs = TrackingServiceConstants.AUTO_MOTION_CAP_EVIDENCE_STREAK_PRESERVE_WINDOW_MS,
            )
            return CaptureReplayMotionFeed(engine, coordinator)
        }
    }
}
