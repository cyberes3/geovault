package com.geovault.tracker.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * STREAM-STATE-MACHINE: explicit, orthogonal model for the live-track streaming session.
 *
 * - [StreamingIntent] answers "do we want to be subscribed?" (a user/app decision).
 * - [StreamingHealth] answers "what is the streaming machinery currently doing?" (a runtime
 *   measurement).
 *
 * Splitting them removes a class of bugs where consumers had to infer "we should auto-resume"
 * from a combination of an implicit intent (non-empty target list) and a noisy single-axis
 * lifecycle enum that conflated "starting" with "reconnecting" and "failed transient" with
 * "failed permanent".
 */
sealed class StreamingIntent {
    data object Idle : StreamingIntent()
    data class Wanted(val targets: Set<String>) : StreamingIntent()
}

enum class StreamingHealth {
    /** No socket; either fresh process or we have torn it down deliberately. */
    Stopped,
    /** First-time connection attempt for the current intent. */
    Starting,
    /** WebSocket upgrade complete and messages flowing. */
    Running,
    /** Lost connectivity; orchestrator is between transient retries. */
    Reconnecting,
    /** Last attempt failed with a transient class; still inside the retry budget. */
    FailedTransient,
    /** Permanently failed (e.g. AUTH burned past its budget); will not retry without user action. */
    FailedPermanent,
}

/**
 * Snapshot of the current streaming session. Constructed from [intent] and [health]; the rest of
 * the surface is derived from those two axes.
 */
data class LiveStreamRuntimeSnapshot(
    val intent: StreamingIntent = StreamingIntent.Idle,
    val health: StreamingHealth = StreamingHealth.Stopped,
    val activeTrackerIds: Set<String> = emptySet(),
    val failureReason: String? = null,
) {
    /** True when something has expressed an intent to subscribe; independent of health. */
    val wantsSubscription: Boolean get() = intent is StreamingIntent.Wanted

    /** True only when health is [StreamingHealth.Running]; useful for "is the stream healthy?" UX. */
    val subscriptionHealthy: Boolean get() = health == StreamingHealth.Running

    /**
     * True when the streaming session is over for any reason — either the user stopped it
     * cleanly, or the orchestrator decided the failure is permanent. Map-lease cleanup, history
     * restore-on-stop, and other "session is done" hooks key off this so a permanent failure
     * doesn't get treated as a transient blip that will eventually retry.
     */
    val subscriptionEnded: Boolean
        get() = health == StreamingHealth.Stopped || health == StreamingHealth.FailedPermanent

    /**
     * True when the streaming service should currently own a foreground notification. Excludes
     * the [StreamingHealth.Stopped] terminal state so post-stop reshow attempts don't accidentally
     * resurrect the FGS.
     */
    val shouldOwnForeground: Boolean
        get() = wantsSubscription && health != StreamingHealth.Stopped
}

object LiveStreamRuntimeStateStore {
    private val _state = MutableStateFlow(LiveStreamRuntimeSnapshot())
    val state: StateFlow<LiveStreamRuntimeSnapshot> = _state.asStateFlow()
    private val lock = Any()

    fun update(transform: (LiveStreamRuntimeSnapshot) -> LiveStreamRuntimeSnapshot) {
        synchronized(lock) {
            _state.value = transform(_state.value)
        }
    }
}
