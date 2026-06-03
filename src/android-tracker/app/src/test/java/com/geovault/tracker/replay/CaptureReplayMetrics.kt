package com.geovault.tracker.replay

import com.geovault.tracker.policy.TrackPointDecisionMetrics
import com.geovault.tracker.policy.TrackPointRejectReason

object CaptureReplayMetrics {
    fun toDecisionMetrics(frame: CaptureReplayFrame): TrackPointDecisionMetrics {
        return TrackPointDecisionMetrics(
            rawDistanceMeters = frame.rawDistanceMeters,
            effectiveDistanceMeters = frame.effectiveDistanceMeters,
            elapsedSeconds = frame.elapsedSeconds,
            impliedSpeedMps = frame.impliedSpeedMps,
            accuracyMeters = frame.accuracy,
            rollingAverageStepMeters = 0.0,
            capCandidateMeters = 180.0,
            decision = if (frame.accepted) "accepted" else "rejected",
            reason = frame.policy,
            rawLatitude = frame.lat,
            rawLongitude = frame.lon,
            committedLatitude = frame.committedLat,
            committedLongitude = frame.committedLon,
        )
    }

    fun toRejectReason(frame: CaptureReplayFrame): TrackPointRejectReason? {
        if (frame.reject == "none") {
            return null
        }
        return TrackPointRejectReason.valueOf(frame.reject)
    }

    fun vettedSpeedMps(frame: CaptureReplayFrame): Float {
        return if (frame.elapsedSeconds > 0.0) {
            (frame.effectiveDistanceMeters / frame.elapsedSeconds).toFloat().coerceAtLeast(0f)
        } else {
            0f
        }
    }
}
