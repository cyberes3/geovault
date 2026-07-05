package com.geovault.tracker.location

data class StreamingLifecycleState(
    val lifecycleState: TrackingLifecycleState = TrackingLifecycleState.STOPPED,
    val failureReason: String? = null,
    val reconnectAttempt: Int = 0,
)

enum class StreamingLifecycleEvent {
    StartRequested,
    RetryRequested,
    Connected,
    RecoverableFailure,
    PermanentFailure,
    StopRequested,
}

enum class StreamingFailureClass {
    TRANSIENT,
    AUTH,
    PERMANENT,
}

object StreamingLifecycleOrchestrator {
    private const val BASE_TRANSIENT_DELAY_MS = 3_000L
    private const val MAX_TRANSIENT_DELAY_MS = 60_000L
    private const val AUTH_DELAY_MS = 30_000L
    /**
     * Hard cap for AUTH retries. Once exceeded, the failure is reclassified as PERMANENT so the
     * orchestrator stops looping on a token that the server keeps rejecting.
     */
    const val MAX_AUTH_RETRY_ATTEMPTS = 3

    fun transition(
        current: StreamingLifecycleState,
        event: StreamingLifecycleEvent,
        failureReason: String? = null,
    ): StreamingLifecycleState {
        return when (event) {
            StreamingLifecycleEvent.StartRequested -> StreamingLifecycleState(
                lifecycleState = TrackingLifecycleState.STARTING,
                failureReason = null,
                reconnectAttempt = 0,
            )
            StreamingLifecycleEvent.RetryRequested -> StreamingLifecycleState(
                lifecycleState = TrackingLifecycleState.STARTING,
                failureReason = current.failureReason,
                reconnectAttempt = current.reconnectAttempt,
            )
            StreamingLifecycleEvent.Connected -> StreamingLifecycleState(
                lifecycleState = TrackingLifecycleState.RUNNING,
                failureReason = null,
                reconnectAttempt = 0,
            )
            StreamingLifecycleEvent.RecoverableFailure -> StreamingLifecycleState(
                lifecycleState = TrackingLifecycleState.FAILED,
                failureReason = failureReason ?: current.failureReason,
                reconnectAttempt = (current.reconnectAttempt + 1).coerceAtMost(8),
            )
            StreamingLifecycleEvent.PermanentFailure -> StreamingLifecycleState(
                lifecycleState = TrackingLifecycleState.FAILED,
                failureReason = failureReason ?: current.failureReason,
                reconnectAttempt = 0,
            )
            StreamingLifecycleEvent.StopRequested -> StreamingLifecycleState(
                lifecycleState = TrackingLifecycleState.STOPPED,
                failureReason = null,
                reconnectAttempt = 0,
            )
        }
    }

    /**
     * [jitterFraction] defaults to 0 (no jitter, fully deterministic) so existing callers/tests
     * are unaffected; [com.geovault.tracker.LiveTrackStreamingService] passes
     * [com.geovault.tracker.streaming.StreamingConfig.retryJitterFraction] explicitly to avoid
     * every client reconnecting in lockstep after a shared outage (thundering herd).
     */
    fun nextReconnectDelayMs(
        reconnectAttempt: Int,
        failureClass: StreamingFailureClass,
        jitterFraction: Double = 0.0,
        jitterRandom: () -> Double = Math::random,
    ): Long {
        val baseDelayMs = when (failureClass) {
            StreamingFailureClass.TRANSIENT -> {
                val clamped = reconnectAttempt.coerceIn(1, 8)
                (BASE_TRANSIENT_DELAY_MS * (1L shl (clamped - 1))).coerceAtMost(MAX_TRANSIENT_DELAY_MS)
            }
            StreamingFailureClass.AUTH -> AUTH_DELAY_MS
            StreamingFailureClass.PERMANENT -> return Long.MAX_VALUE
        }
        if (jitterFraction <= 0.0) return baseDelayMs
        // jitterRandom() in [0, 1) -> multiplier in [1 - jitterFraction, 1 + jitterFraction).
        val multiplier = 1.0 + (jitterRandom() * 2.0 - 1.0) * jitterFraction
        return (baseDelayMs * multiplier).toLong().coerceAtLeast(0L)
    }

    /**
     * AUTH failures get a small bounded retry window. After [MAX_AUTH_RETRY_ATTEMPTS] consecutive
     * AUTH failures we escalate to PERMANENT so the connect loop stops, the user is shown a
     * sign-in prompt, and we stop battery-draining background reconnects.
     */
    fun classifyAuthFailure(reconnectAttempt: Int): StreamingFailureClass {
        return if (reconnectAttempt >= MAX_AUTH_RETRY_ATTEMPTS) {
            StreamingFailureClass.PERMANENT
        } else {
            StreamingFailureClass.AUTH
        }
    }
}
