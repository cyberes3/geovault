package com.geovault.tracker.presentation

import com.geovault.tracker.streaming.ConnectionPhase
import com.geovault.tracker.streaming.LiveStreamSubscriptionState

enum class TrackerMapStreamingStatus {
    INACTIVE,
    CONNECTING,
    LIVE,
    RECONNECTING,
    FAILED,
}

data class TrackerMapStreamingStatusUiModel(
    val status: TrackerMapStreamingStatus = TrackerMapStreamingStatus.INACTIVE,
    val activeCount: Int = 0,
    val failureReason: String? = null,
)

/**
 * STREAM-STATE-MACHINE: status mapping is driven by [ConnectionPhase] directly so consumers see
 * distinct UI for "starting fresh" vs "reconnecting after a drop", and so a permanent failure is
 * not silently lumped in with a transient blip.
 */
object TrackerMapStreamingStatusPolicy {
    fun resolve(
        snapshot: LiveStreamSubscriptionState,
        streamTargetIds: Set<String>,
    ): TrackerMapStreamingStatusUiModel {
        val desiredIds = normalizeIds(streamTargetIds)
        if (!snapshot.wantsSubscription && desiredIds.isEmpty()) {
            return TrackerMapStreamingStatusUiModel()
        }

        val activeIds = normalizeIds(snapshot.activeTargets)
        val activeCount = activeIds.size
        // LEASE-INTENT COMPARISON: match against the repository's own merged lease intent
        // (the union of every owner's -- map's and params' -- current lease), not the map's
        // independently-resolved `streamTargetIds`. The two can legitimately disagree for a
        // perfectly healthy connection -- e.g. Params holds a lease for a tracker the map
        // doesn't display, so `activeTargets` correctly includes it too even though the map's
        // own desired set doesn't. Comparing against the map's narrower set alone would
        // misclassify that (and any transient lag between a roster change updating the map's
        // plan and the repository's leases catching up to it) as a dropped connection.
        val leaseIntentIds = normalizeIds(snapshot.mergedTargets)
        val desiredMatched = leaseIntentIds.isNotEmpty() && activeIds == leaseIntentIds

        return when (snapshot.connection) {
            ConnectionPhase.STARTING -> TrackerMapStreamingStatusUiModel(
                // COLD-START BOOTSTRAP GUARD: `activeTargets` is pre-populated with the
                // persisted target set as soon as the bootstrap lease seeds (purely so a count
                // can be shown immediately), well before any real connection attempt in this
                // process has happened. Without `hasConnectedThisProcess`, that non-empty count
                // alone would misclassify this very first STARTING as a reconnect.
                status = if (activeCount > 0 && snapshot.hasConnectedThisProcess) {
                    TrackerMapStreamingStatus.RECONNECTING
                } else {
                    TrackerMapStreamingStatus.CONNECTING
                },
                activeCount = activeCount,
            )
            ConnectionPhase.RECONNECTING -> TrackerMapStreamingStatusUiModel(
                status = TrackerMapStreamingStatus.RECONNECTING,
                activeCount = activeCount,
            )
            ConnectionPhase.RUNNING -> TrackerMapStreamingStatusUiModel(
                status = if (desiredMatched) {
                    TrackerMapStreamingStatus.LIVE
                } else if (activeCount > 0) {
                    TrackerMapStreamingStatus.RECONNECTING
                } else {
                    TrackerMapStreamingStatus.CONNECTING
                },
                activeCount = activeCount,
            )
            // FAILED_TRANSIENT means a retry is still pending within the backoff budget (see
            // LiveTrackStreamingService.scheduleReconnect) -- surface it as "Reconnecting", not
            // "Failed", since the user hasn't lost the stream, it's just between attempts.
            // Only FAILED_PERMANENT (retry budget exhausted, or a non-retryable failure class)
            // is a true terminal failure.
            ConnectionPhase.FAILED_TRANSIENT -> TrackerMapStreamingStatusUiModel(
                status = TrackerMapStreamingStatus.RECONNECTING,
                activeCount = activeCount,
                failureReason = snapshot.failureReason,
            )
            ConnectionPhase.FAILED_PERMANENT -> TrackerMapStreamingStatusUiModel(
                status = TrackerMapStreamingStatus.FAILED,
                activeCount = activeCount,
                failureReason = snapshot.failureReason,
            )
            ConnectionPhase.IDLE -> TrackerMapStreamingStatusUiModel()
        }
    }

    private fun normalizeIds(ids: Set<String>): Set<String> {
        return ids.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
    }
}
