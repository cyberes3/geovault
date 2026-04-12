package com.geovault.tracker.presentation

data class TrackerAccuracyFallbackPolicyInput(
    val mode: TrackerMapDisplayMode,
    val runtimeRunning: Boolean,
    val selectedTrackerId: String,
    val displayedTrackerId: String,
    val visibleTrackerIds: Set<String>,
)

object TrackerAccuracyFallbackPolicy {
    fun resolveAllowedFallbackTrackerIds(input: TrackerAccuracyFallbackPolicyInput): Set<String> {
        val visibleIds = input.visibleTrackerIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (visibleIds.isEmpty()) return emptySet()
        val selectedId = input.selectedTrackerId.trim()
        val displayedId = input.displayedTrackerId.trim()
        return when (input.mode) {
            TrackerMapDisplayMode.SINGLE_SESSION -> {
                when {
                    displayedId.isNotEmpty() && displayedId in visibleIds -> setOf(displayedId)
                    selectedId.isNotEmpty() && selectedId in visibleIds -> setOf(selectedId)
                    else -> visibleIds
                }
            }
            TrackerMapDisplayMode.ALL_QUEUE,
            TrackerMapDisplayMode.GROUP_PLACEHOLDER -> visibleIds
        }
    }
}
