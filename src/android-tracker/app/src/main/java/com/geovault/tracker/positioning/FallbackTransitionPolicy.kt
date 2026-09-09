package com.geovault.tracker.positioning

import android.location.Location
import com.geovault.tracker.policy.TrackPointEmissionDecision
import com.geovault.tracker.domain.TrackPoint
import com.geovault.tracker.policy.TrackPointPolicyEngine
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.positioning.config.PositioningPolicyConfig
import com.geovault.tracker.tracking.TrackingServiceConstants

object FallbackTransitionPolicy {
    fun shouldEmitFallbackForTransition(
        previousAcceptedLocation: Location?,
        fallbackCandidateLocation: Location,
        nowMs: Long,
        nowElapsedRealtimeNanos: Long,
    ): Boolean {
        if (previousAcceptedLocation == null) return true
        val trackId = TrackingServiceConstants.FALLBACK_TRANSITION_TRACK_ID
        val config = PositioningPolicyConfig.fallbackTransitionConfig()
        TrackPointPolicyEngine.resetStream(source = TrackPointSource.LOCAL_GPS, trackId = trackId)
        TrackPointPolicyEngine.evaluate(
            event = trackPointEventFromLocation(previousAcceptedLocation, trackId),
            nowMs = previousAcceptedLocation.time,
            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
            config = config,
        )
        val decision = TrackPointPolicyEngine.evaluate(
            event = trackPointEventFromLocation(fallbackCandidateLocation, trackId),
            nowMs = nowMs,
            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
            config = config,
        )
        return decision.accepted || decision.emissionDecision == TrackPointEmissionDecision.SNAP_INTERNAL
    }

    private fun trackPointEventFromLocation(location: Location, trackId: String): TrackPoint {
        return TrackPoint(
            provenance = TrackPointSource.LOCAL_GPS,
            trackerId = trackId,
            longitude = location.longitude,
            latitude = location.latitude,
            timeMs = location.time,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
            elapsedRealtimeNanos = location.elapsedRealtimeNanos,
            gpsSpeedMps = if (location.hasSpeed()) location.speed else null,
            gpsBearingDeg = if (location.hasBearing()) location.bearing else null,
        )
    }
}
