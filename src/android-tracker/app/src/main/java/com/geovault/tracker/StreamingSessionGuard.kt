package com.geovault.tracker

import com.geovault.tracker.streaming.StreamingConfig

internal enum class StreamingSessionReuseDecision {
    /** Same tracker set, socket healthy: do nothing. */
    REUSE,
    /**
     * Different tracker set, but the socket is otherwise healthy (connected, RUNNING, not
     * stale): the server pushes every update over one socket and the client filters by id
     * client-side (see [com.geovault.tracker.LiveTrackStreamingService]'s `filterTrackIds`), so
     * a roster change never needs a new socket — just update `currentTrackerIds` in place.
     */
    HOT_UPDATE,
    NO_SOCKET,
    NOT_RUNNING,
    NO_ACTIVITY,
    STALE_ACTIVITY,
}

internal data class StreamingSessionAssessment(
    val decision: StreamingSessionReuseDecision,
    val activityAgeMs: Long? = null,
)

internal class StreamingSessionGuard(
    private val staleAfterMs: Long = StreamingConfig.sessionStaleAfterMs,
    private val elapsedRealtimeMs: () -> Long,
) {
    private var lastActivityElapsedMs: Long = 0L

    fun markConnected() {
        lastActivityElapsedMs = elapsedRealtimeMs()
    }

    /**
     * Refreshes staleness from the app-level pong only. That reply proves the connection is
     * alive even for a perfectly healthy but currently-idle tracker. Incoming `track_updated`
     * points must not call this -- keying off point recency would conflate a quiet tracker with
     * a dead socket.
     */
    fun markLivenessReceived() {
        lastActivityElapsedMs = elapsedRealtimeMs()
    }

    fun markDisconnected() {
        lastActivityElapsedMs = 0L
    }

    fun assess(
        requestedTrackerIds: Set<String>,
        currentTrackerIds: Set<String>,
        hasSocket: Boolean,
        running: Boolean,
    ): StreamingSessionAssessment {
        // ROSTER-DELTA-HOT-UPDATE: the underlying-connection health checks (socket presence,
        // RUNNING, freshness) are evaluated *before* the tracker-set comparison. A roster change
        // is only ever eligible for an in-place [StreamingSessionReuseDecision.HOT_UPDATE] when
        // the connection is otherwise indistinguishable from a REUSE-eligible one; if the socket
        // is missing, not yet RUNNING, or already stale, a real reconnect is needed regardless of
        // whether the tracker set also happens to differ.
        if (!hasSocket) return StreamingSessionAssessment(StreamingSessionReuseDecision.NO_SOCKET)
        if (!running) {
            return StreamingSessionAssessment(StreamingSessionReuseDecision.NOT_RUNNING)
        }
        if (lastActivityElapsedMs <= 0L) {
            return StreamingSessionAssessment(StreamingSessionReuseDecision.NO_ACTIVITY)
        }
        val ageMs = (elapsedRealtimeMs() - lastActivityElapsedMs).coerceAtLeast(0L)
        if (ageMs > staleAfterMs) {
            return StreamingSessionAssessment(
                decision = StreamingSessionReuseDecision.STALE_ACTIVITY,
                activityAgeMs = ageMs,
            )
        }
        val decision = if (requestedTrackerIds != currentTrackerIds) {
            StreamingSessionReuseDecision.HOT_UPDATE
        } else {
            StreamingSessionReuseDecision.REUSE
        }
        return StreamingSessionAssessment(decision = decision, activityAgeMs = ageMs)
    }

    companion object {
        fun createDefault(): StreamingSessionGuard {
            return StreamingSessionGuard(elapsedRealtimeMs = android.os.SystemClock::elapsedRealtime)
        }
    }
}
