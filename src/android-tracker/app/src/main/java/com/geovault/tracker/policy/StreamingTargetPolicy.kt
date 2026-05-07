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
}
