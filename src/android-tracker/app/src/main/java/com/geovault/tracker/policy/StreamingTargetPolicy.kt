package com.geovault.tracker.policy

data class StreamingTargetPolicyInput(
    val requestedTrackerIds: Collection<String>,
    val locallyRecordedTrackerIds: Collection<String> = emptySet(),
)

object StreamingTargetPolicy {
    fun normalizeTrackerIds(ids: Collection<String>): Set<String> {
        return ids.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
    }

    /**
     * The only streaming exclusion is the locally-recorded tracker; its live GPS feed is the
     * source of truth on this device, so we never round-trip it through the websocket. Per-mode
     * targeting (e.g. SINGLE_SESSION on the selected tracker is history-only) is decided by
     * [com.geovault.tracker.presentation.TrackerMapSessionProjector] before the request reaches
     * this policy — by the time we get here, `requestedTrackerIds` already encodes the intent.
     */
    fun remoteSubscriptionTargets(input: StreamingTargetPolicyInput): Set<String> {
        val excludedIds = normalizeTrackerIds(input.locallyRecordedTrackerIds)
        return normalizeTrackerIds(input.requestedTrackerIds) - excludedIds
    }

    /**
     * Shared single-tracker decision used by both [com.geovault.tracker.presentation.TrackerMapSessionProjector]
     * (SINGLE_SESSION mode) and `TrackerParamsStreamingController`: viewing the user's own
     * selected tracker is always history-only (backed by the local GPS feed / local Room queue),
     * never a remote websocket subscription — regardless of which surface is asking. Unifying
     * this here means the map and the params screen can never disagree about this one rule.
     */
    fun isHistoryOnlyView(viewedTrackerId: String, selectedTrackerId: String): Boolean {
        val viewed = viewedTrackerId.trim()
        val selected = selectedTrackerId.trim()
        return viewed.isNotEmpty() && selected.isNotEmpty() && viewed == selected
    }
}
