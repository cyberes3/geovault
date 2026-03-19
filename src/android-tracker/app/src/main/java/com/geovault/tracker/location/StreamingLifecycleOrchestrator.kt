package com.geovault.tracker.location

data class StreamingLifecycleState(
    val lifecycleState: TrackingLifecycleState = TrackingLifecycleState.STOPPED,
    val failureReason: String? = null,
    val reconnectAttempt: Int = 0
)

enum class StreamingLifecycleEvent {
    StartRequested,
    RetryRequested,
    Connected,
    RecoverableFailure,
    PermanentFailure,
    StopRequested
}

enum class StreamingFailureClass {
    TRANSIENT,
    AUTH,
    PERMANENT
}

object StreamingLifecycleOrchestrator {
    private const val BASE_TRANSIENT_DELAY_MS = 3_000L
    private const val MAX_TRANSIENT_DELAY_MS = 60_000L
    private const val AUTH_DELAY_MS = 30_000L

    fun transition(
        current: StreamingLifecycleState,
        event: StreamingLifecycleEvent,
        failureReason: String? = null
    ): StreamingLifecycleState {
        return when (event) {
            StreamingLifecycleEvent.StartRequested -> StreamingLifecycleState(
                lifecycleState = TrackingLifecycleState.STARTING,
                failureReason = null,
                reconnectAttempt = 0
            )
            StreamingLifecycleEvent.RetryRequested -> StreamingLifecycleState(
                lifecycleState = TrackingLifecycleState.STARTING,
                failureReason = current.failureReason,
                reconnectAttempt = current.reconnectAttempt
            )
            StreamingLifecycleEvent.Connected -> StreamingLifecycleState(
                lifecycleState = TrackingLifecycleState.RUNNING,
                failureReason = null,
                reconnectAttempt = 0
            )
            StreamingLifecycleEvent.RecoverableFailure -> StreamingLifecycleState(
                lifecycleState = TrackingLifecycleState.FAILED,
                failureReason = failureReason ?: current.failureReason,
                reconnectAttempt = (current.reconnectAttempt + 1).coerceAtMost(8)
            )
            StreamingLifecycleEvent.PermanentFailure -> StreamingLifecycleState(
                lifecycleState = TrackingLifecycleState.FAILED,
                failureReason = failureReason ?: current.failureReason,
                reconnectAttempt = 0
            )
            StreamingLifecycleEvent.StopRequested -> StreamingLifecycleState(
                lifecycleState = TrackingLifecycleState.STOPPED,
                failureReason = null,
                reconnectAttempt = 0
            )
        }
    }

    fun nextReconnectDelayMs(
        reconnectAttempt: Int,
        failureClass: StreamingFailureClass
    ): Long {
        return when (failureClass) {
            StreamingFailureClass.TRANSIENT -> {
                val clamped = reconnectAttempt.coerceIn(1, 8)
                (BASE_TRANSIENT_DELAY_MS * (1L shl (clamped - 1))).coerceAtMost(MAX_TRANSIENT_DELAY_MS)
            }
            StreamingFailureClass.AUTH -> AUTH_DELAY_MS
            StreamingFailureClass.PERMANENT -> Long.MAX_VALUE
        }
    }
}
