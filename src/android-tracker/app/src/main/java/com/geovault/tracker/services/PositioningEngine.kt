package com.geovault.tracker.services

import android.location.Location
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.policy.TrackPointDecision
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointRejectReason
import com.geovault.tracker.policy.filter.LocationFilterConfig

data class PositioningDecisionTrace(
    val trackId: String,
    val event: TrackPointEvent,
    val decision: TrackPointDecision,
    val nowMs: Long,
) {
    fun summary(): String {
        val metrics = decision.metrics
        return "track=$trackId ts=${event.timestampMs} lat=${event.lat} lon=${event.lon} " +
            "acc=${event.accuracyMeters ?: -1f} accepted=${decision.accepted} " +
            "emission=${decision.emissionDecision} reject=${decision.rejectReason ?: "none"} " +
            "policy=${metrics?.reason ?: "none"} raw=${metrics?.rawDistanceMeters ?: -1.0} " +
            "effective=${metrics?.effectiveDistanceMeters ?: -1.0} dt=${metrics?.elapsedSeconds ?: -1.0} " +
            "speed=${metrics?.impliedSpeedMps ?: -1.0} committedLat=${metrics?.committedLatitude ?: "none"} " +
            "committedLon=${metrics?.committedLongitude ?: "none"} now=$nowMs"
    }
}

class PositioningDecisionTraceBuffer(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val traces = ArrayDeque<PositioningDecisionTrace>()

    @Synchronized
    fun add(trace: PositioningDecisionTrace) {
        traces.addLast(trace)
        while (traces.size > capacity) traces.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<PositioningDecisionTrace> = traces.toList()

    companion object {
        const val DEFAULT_CAPACITY = 8
    }
}

class PositioningEngine(
    onForcedLocalReanchor: (LocalReanchorEvent) -> Unit = {},
    private val traceBuffer: PositioningDecisionTraceBuffer = PositioningDecisionTraceBuffer(),
) {
    private val localTrackPointState = LocalTrackPointStateCoordinator(onForcedLocalReanchor)

    fun resetSession(trackId: String) {
        localTrackPointState.resetSession(trackId)
    }

    fun eventForLocation(
        trackId: String,
        location: Location,
        isMockLocation: Boolean,
        nowMs: Long,
    ): TrackPointEvent {
        return localTrackPointState.eventForLocation(
            trackId = trackId,
            location = location,
            isMockLocation = isMockLocation,
            nowMs = nowMs,
        )
    }

    fun evaluate(
        trackId: String,
        event: TrackPointEvent,
        nowMs: Long,
        nowElapsedRealtimeNanos: Long,
        config: LocationFilterConfig,
    ): TrackPointDecision {
        val decision = localTrackPointState.evaluate(
            trackId = trackId,
            event = event,
            nowMs = nowMs,
            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
            config = config,
        )
        recordDecision(trackId = trackId, event = event, decision = decision, nowMs = nowMs)
        return decision
    }

    fun validateBypass(trackId: String, canonical: TrackPointEvent): TrackPointRejectReason? {
        return localTrackPointState.validateBypass(trackId = trackId, canonical = canonical)
    }

    fun acceptBypass(trackId: String, canonical: TrackPointEvent, config: LocationFilterConfig) {
        localTrackPointState.acceptBypass(trackId = trackId, canonical = canonical, config = config)
    }

    fun recentDecisionTrace(): List<PositioningDecisionTrace> = traceBuffer.snapshot()

    private fun recordDecision(
        trackId: String,
        event: TrackPointEvent,
        decision: TrackPointDecision,
        nowMs: Long,
    ) {
        val trace = PositioningDecisionTrace(
            trackId = trackId,
            event = event,
            decision = decision,
            nowMs = nowMs,
        )
        traceBuffer.add(trace)
        if (!decision.accepted || decision.metrics?.reason?.contains("resume", ignoreCase = true) == true) {
            GeoVaultCaptureLog.i(TAG, "positioning_decision_trace ${trace.summary()}")
        }
    }

    companion object {
        private const val TAG = "PositioningEngine"
    }
}
