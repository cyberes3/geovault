package com.geovault.tracker

import com.geovault.tracker.location.TrackingLifecycleState

internal enum class StreamingSessionReuseDecision {
    REUSE,
    TRACKER_SET_CHANGED,
    NO_SOCKET,
    NOT_RUNNING,
    NO_ACTIVITY,
    STALE_ACTIVITY
}

internal data class StreamingSessionAssessment(
    val decision: StreamingSessionReuseDecision,
    val activityAgeMs: Long? = null
)

/**
 * Encapsulates reuse policy for an existing live-streaming websocket session.
 *
 * This keeps session health decisions deterministic and testable.
 */
internal class StreamingSessionGuard(
    private val staleAfterMs: Long = DEFAULT_STALE_AFTER_MS,
    private val elapsedRealtimeMs: () -> Long
) {
    private var lastActivityElapsedMs: Long = 0L

    fun markConnected() {
        lastActivityElapsedMs = elapsedRealtimeMs()
    }

    fun markMessageReceived() {
        lastActivityElapsedMs = elapsedRealtimeMs()
    }

    fun markDisconnected() {
        lastActivityElapsedMs = 0L
    }

    fun assess(
        requestedTrackerIds: Set<String>,
        currentTrackerIds: Set<String>,
        hasSocket: Boolean,
        lifecycleState: TrackingLifecycleState
    ): StreamingSessionAssessment {
        if (requestedTrackerIds != currentTrackerIds) {
            return StreamingSessionAssessment(StreamingSessionReuseDecision.TRACKER_SET_CHANGED)
        }
        if (!hasSocket) {
            return StreamingSessionAssessment(StreamingSessionReuseDecision.NO_SOCKET)
        }
        if (lifecycleState != TrackingLifecycleState.RUNNING) {
            return StreamingSessionAssessment(StreamingSessionReuseDecision.NOT_RUNNING)
        }
        if (lastActivityElapsedMs <= 0L) {
            return StreamingSessionAssessment(StreamingSessionReuseDecision.NO_ACTIVITY)
        }
        val ageMs = (elapsedRealtimeMs() - lastActivityElapsedMs).coerceAtLeast(0L)
        if (ageMs > staleAfterMs) {
            return StreamingSessionAssessment(
                decision = StreamingSessionReuseDecision.STALE_ACTIVITY,
                activityAgeMs = ageMs
            )
        }
        return StreamingSessionAssessment(
            decision = StreamingSessionReuseDecision.REUSE,
            activityAgeMs = ageMs
        )
    }

    companion object {
        private const val DEFAULT_STALE_AFTER_MS = 45_000L

        fun createDefault(): StreamingSessionGuard = StreamingSessionGuard(
            elapsedRealtimeMs = android.os.SystemClock::elapsedRealtime
        )
    }
}
