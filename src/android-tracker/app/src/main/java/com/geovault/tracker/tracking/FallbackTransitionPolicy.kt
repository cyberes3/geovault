package com.geovault.tracker.tracking

import android.location.Location
import android.os.SystemClock
import com.geovault.tracker.policy.TrackPointEmissionDecision
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointPolicyEngine
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.services.PositioningPolicyConfig

object FallbackTransitionPolicy {
    fun shouldEmitFallbackForTransition(
        previousAcceptedLocation: Location?,
        fallbackCandidateLocation: Location,
        nowMs: Long,
    ): Boolean {
        if (previousAcceptedLocation == null) return true
        val trackId = TrackingServiceConstants.FALLBACK_TRANSITION_TRACK_ID
        val config = PositioningPolicyConfig.fallbackTransitionConfig()
        TrackPointPolicyEngine.resetStream(source = TrackPointSource.LOCAL_GPS, trackId = trackId)
        TrackPointPolicyEngine.evaluate(
            event = trackPointEventFromLocation(previousAcceptedLocation, trackId),
            nowMs = previousAcceptedLocation.time,
            nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
            config = config,
        )
        val decision = TrackPointPolicyEngine.evaluate(
            event = trackPointEventFromLocation(fallbackCandidateLocation, trackId),
            nowMs = nowMs,
            nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
            config = config,
        )
        return decision.accepted || decision.emissionDecision == TrackPointEmissionDecision.SNAP_INTERNAL
    }

    private fun trackPointEventFromLocation(location: Location, trackId: String): TrackPointEvent {
        return TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = trackId,
            lon = location.longitude,
            lat = location.latitude,
            timestampMs = location.time,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
            elapsedRealtimeNanos = location.elapsedRealtimeNanos,
            gpsSpeedMps = if (location.hasSpeed()) location.speed else null,
            gpsBearingDeg = if (location.hasBearing()) location.bearing else null,
        )
    }
}
