package com.geovault.tracker.presentation

import com.geovault.tracker.policy.StreamingTargetPolicy
import com.geovault.tracker.streaming.LiveStreamSubscriptionRepository
import com.geovault.tracker.streaming.OwnerLease
import com.geovault.tracker.streaming.StreamingOwner

/**
 * Translates the Tracker Params screen's lifecycle into a [StreamingOwner.PARAMS] lease on
 * [LiveStreamSubscriptionRepository].
 *
 * Because the repository always computes the *union* of every owner's lease, Params no longer
 * needs a "is the map already streaming this tracker?" special case (the old `AlreadyActive` /
 * `ExpandedExistingStream` ownership machinery): it just always holds its own lease for whatever
 * tracker it's viewing. If the map is already streaming the same tracker, the merged union is
 * simply unchanged; if the map later stops, Params' own lease keeps the subscription alive
 * without needing a separate recovery path. This is what eliminates the previous "Params can't
 * recover once the map stops" bug — by deleting the code path that caused it rather than
 * patching around it.
 *
 * Re-applying on connection-health degradation (e.g. a stale-but-"Running" stream) is no longer
 * this class's job either: [com.geovault.tracker.streaming.LiveStreamSubscriptionRepository]'s
 * liveness watchdog handles that centrally for every owner, so this controller only needs to
 * react to *lease-shape* changes (which tracker, whether it's the local recorder).
 */
internal class TrackerParamsStreamingController(
    private val repository: LiveStreamSubscriptionRepository,
) {
    private var hasLease = false

    fun onScreenStarted(
        trackerId: String,
        trackerName: String?,
        selectedTrackerId: String,
        trackingRunning: Boolean,
    ) {
        val id = trackerId.trim()
        val lease = if (id.isEmpty() || StreamingTargetPolicy.isHistoryOnlyView(id, selectedTrackerId)) {
            null
        } else {
            OwnerLease(
                trackerIds = setOf(id),
                displayName = trackerName?.trim()?.ifBlank { null },
                locallyRecordedTrackerId = selectedTrackerId.trim().takeIf { trackingRunning },
            )
        }
        repository.setLease(StreamingOwner.PARAMS, lease)
        hasLease = lease != null
    }

    fun onScreenStopped() {
        if (!hasLease) return
        hasLease = false
        repository.setLease(StreamingOwner.PARAMS, null)
    }
}
