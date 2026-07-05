package com.geovault.tracker.presentation

import com.geovault.tracker.streaming.LiveStreamSubscriptionRepository
import com.geovault.tracker.streaming.OwnerLease
import com.geovault.tracker.streaming.StreamingOwner

/**
 * Translates a resolved [TrackerMapStreamingPlan] into a [StreamingOwner.MAP] lease on
 * [LiveStreamSubscriptionRepository]. Pure translation — all merge/dedupe/dispatch logic lives
 * in the repository; this class only knows how to turn "here is what should be streamed right
 * now" into "does the map want a lease, and if so, for what."
 *
 * Takes the resolved target ids as a plain parameter rather than a [TrackerMapUiState] on
 * purpose: the caller always has a just-computed [TrackerMapStreamingPlan] in hand (the plan is
 * the single source of truth for "what should be streamed"), so threading `state.streamTargetIds`
 * through here as well would just be a second, independently-stale copy of the same decision.
 */
internal class LiveTrackStreamingReconciler(
    private val repository: LiveStreamSubscriptionRepository,
) {
    /** True if the map currently holds a (possibly empty) [StreamingOwner.MAP] lease. */
    fun hasMapStreamingLease(): Boolean = repository.state.value.leases[StreamingOwner.MAP] != null

    /** Reads and clears the map's lease-held flag in one step; used to fire post-stop cleanup exactly once. */
    fun consumeStoppedMapStreamingLease(): Boolean {
        val had = hasMapStreamingLease()
        if (had) repository.setLease(StreamingOwner.MAP, null)
        return had
    }

    /** Unconditional stop (e.g. map context reset). */
    fun stopForegroundStreaming() {
        repository.setLease(StreamingOwner.MAP, null)
    }

    fun reconcile(
        mode: TrackerMapDisplayMode,
        remoteSubscriptionIds: Set<String>,
        locallyRecordedTrackerId: String,
        effectiveDisplayedId: String,
        effectiveDisplayedName: String,
    ) {
        val command = TrackerMapStreamingCoordinator.resolve(
            TrackerMapStreamingDecisionInput(
                mode = mode,
                streamTargetIds = remoteSubscriptionIds,
                displayedTrackerId = effectiveDisplayedId,
                displayedTrackerName = effectiveDisplayedName,
            )
        )
        when (command) {
            is TrackerMapStreamingCommand.Start -> {
                // STREAMING EXCLUSION: the only tracker that should never be streamed is the
                // locally-recorded one. Per-mode targeting is already encoded in
                // command.trackerIds by the projector; the only thing this layer adds to the
                // per-owner lease is the locally-recorded id, so a parallel Params lease can't
                // accidentally subscribe to the actively-recorded tracker via its half of the
                // merged plan.
                repository.setLease(
                    StreamingOwner.MAP,
                    OwnerLease(
                        trackerIds = command.trackerIds,
                        displayName = command.trackerName,
                        locallyRecordedTrackerId = locallyRecordedTrackerId,
                    ),
                )
            }
            TrackerMapStreamingCommand.Stop -> {
                repository.setLease(StreamingOwner.MAP, null)
            }
            TrackerMapStreamingCommand.NoOp -> {
                // Single-session with no resolved displayed id yet cannot own a map streaming
                // lease; Params may still keep streaming alone. Safe to call unconditionally —
                // setLease(MAP, null) is a no-op dispatch when the map didn't hold a lease to
                // begin with (including the very first reconcile tick at cold start, before the
                // repository's bootstrap seed is consumed by a real lease).
                if (mode == TrackerMapDisplayMode.SINGLE_SESSION && effectiveDisplayedId.trim().isEmpty()) {
                    repository.setLease(StreamingOwner.MAP, null)
                }
            }
        }
    }
}
