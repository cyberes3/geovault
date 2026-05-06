package com.geovault.tracker.policy

data class StreamingTargetPolicyInput(
    val requestedTrackerIds: Collection<String>,
    val selectedTrackerId: String = "",
    val locallyRecordedTrackerIds: Collection<String> = emptySet(),
    val excludedTrackerIds: Collection<String> = emptySet(),
)

object StreamingTargetPolicy {
    fun normalizeTrackerIds(ids: Collection<String>): Set<String> {
        return ids.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
    }

    fun selectedTrackerExclusion(selectedTrackerId: String): Set<String> {
        return selectedTrackerId.trim().takeIf { it.isNotEmpty() }?.let(::setOf).orEmpty()
    }

    fun remoteSubscriptionTargets(input: StreamingTargetPolicyInput): Set<String> {
        val excludedIds = normalizeTrackerIds(input.excludedTrackerIds) +
            normalizeTrackerIds(input.locallyRecordedTrackerIds) +
            selectedTrackerExclusion(input.selectedTrackerId)
        return normalizeTrackerIds(input.requestedTrackerIds) - excludedIds
    }
}
