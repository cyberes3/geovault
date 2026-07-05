package com.geovault.tracker.streaming

import com.geovault.tracker.policy.StreamingTargetPolicy
import com.geovault.tracker.policy.StreamingTargetPolicyInput

/**
 * The two independent callers that can want a live-track websocket subscription. Each owner
 * holds at most one [OwnerLease] at a time; [LiveStreamSubscriptionRepository] merges them.
 */
enum class StreamingOwner {
    MAP,
    PARAMS,
}

/**
 * One owner's streaming request. `locallyRecordedTrackerId` is carried per-lease (rather than
 * computed globally) so each owner declares its own view of "what is being recorded locally
 * right now" — the repository unions these across owners before excluding them from the merged
 * subscription, so the locally-recorded tracker never round-trips through the websocket
 * regardless of which owner's request happened to include it.
 */
data class OwnerLease(
    val trackerIds: Set<String>,
    val displayName: String? = null,
    val locallyRecordedTrackerId: String? = null,
)

/**
 * Connection-health axis, reported exclusively by [LiveTrackStreamingService] via
 * [LiveStreamSubscriptionRepository.reportConnectionUpdate]. Orthogonal to "what do we want
 * streamed" (owned by leases): a caller can want a subscription while the connection is
 * RECONNECTING, and the connection can still be RUNNING for one instant after a lease is
 * cleared but before the stop command lands.
 */
enum class ConnectionPhase {
    /** No socket; either fresh process or deliberately torn down. */
    IDLE,
    /** First-time connection attempt for the current lease set. */
    STARTING,
    /** WebSocket upgrade complete and messages flowing. */
    RUNNING,
    /** Lost connectivity; service is between transient retries. */
    RECONNECTING,
    /** Last attempt failed with a transient class; still inside the retry budget. */
    FAILED_TRANSIENT,
    /** Permanently failed (e.g. AUTH burned past its budget); will not retry without user action. */
    FAILED_PERMANENT,
}

/** Forces [LiveStreamSubscriptionRepository.requestReapply] to bypass its own dedupe gate. */
enum class ReapplyReason {
    /** Liveness watchdog detected a stale-but-"Running" session. */
    STALE_CONNECTION,
    /** A caller (e.g. resume-from-background) wants an unconditional re-apply. */
    MANUAL,
    /** A dispatch failure cleared the gate; the next matching request should retry cleanly. */
    FAILURE_RECOVERY,
}

/** Reason recorded when every lease is dropped and a stop is unconditionally dispatched. */
enum class ClearReason {
    LOGOUT,
    ACCOUNT_RESET,
}

data class DispatchedCommand(
    val trackerIds: Set<String>,
    val displayName: String?,
)

/**
 * Single source of truth for "what should be streaming" (merged from [leases], with the
 * bootstrap seed folded in transparently until it is consumed or expires) and "what is actually
 * streaming" ([connection]/[activeTargets]).
 */
data class LiveStreamSubscriptionState(
    val leases: Map<StreamingOwner, OwnerLease> = emptyMap(),
    internal val bootstrapLease: OwnerLease? = null,
    val connection: ConnectionPhase = ConnectionPhase.IDLE,
    val activeTargets: Set<String> = emptySet(),
    val failureReason: String? = null,
    val lastDispatchedCommand: DispatchedCommand? = null,
) {
    private val effectiveLeases: List<OwnerLease>
        get() = leases.values + listOfNotNull(bootstrapLease)

    /** Union of every owner's requested ids (plus any still-live bootstrap seed), minus every owner's locally-recorded id. */
    val mergedTargets: Set<String> by lazy {
        StreamingTargetPolicy.remoteSubscriptionTargets(
            StreamingTargetPolicyInput(
                requestedTrackerIds = effectiveLeases.flatMap { it.trackerIds },
                locallyRecordedTrackerIds = effectiveLeases.mapNotNull { it.locallyRecordedTrackerId },
            )
        )
    }

    /** Notification-worthy display name; only meaningful (non-null) when exactly one tracker is targeted. */
    val displayName: String? by lazy {
        if (mergedTargets.size == 1) {
            effectiveLeases.firstNotNullOfOrNull { it.displayName?.trim()?.ifBlank { null } }
        } else {
            null
        }
    }

    /** True when some owner (or the not-yet-expired bootstrap seed) wants a subscription. */
    val wantsSubscription: Boolean get() = mergedTargets.isNotEmpty()

    /** True only when the connection is actually [ConnectionPhase.RUNNING]. */
    val subscriptionHealthy: Boolean get() = connection == ConnectionPhase.RUNNING

    /**
     * True when the streaming session is over for any reason — deliberately stopped, or
     * permanently failed. Lease cleanup / history-restore-on-stop hooks key off this so a
     * permanent failure isn't mistaken for a transient blip that will eventually retry.
     */
    val subscriptionEnded: Boolean
        get() = connection == ConnectionPhase.IDLE || connection == ConnectionPhase.FAILED_PERMANENT
}
