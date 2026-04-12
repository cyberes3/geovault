package com.geovault.tracker.presentation

data class TrackerMapStreamTargetInput(
    val mode: TrackerMapDisplayMode,
    val runtimeRunning: Boolean,
    val selectedTrackerId: String,
    val displayedTrackerId: String,
    val rosterTrackerIds: Set<String>,
    val groupSelection: TrackerMapGroupModeSelection,
)

data class TrackerMapStreamTargetResult(
    val streamTargetIds: Set<String>,
    val resolvedGroupId: String,
)

object TrackerMapStreamTargetCoordinator {
    fun resolve(input: TrackerMapStreamTargetInput): TrackerMapStreamTargetResult {
        val streamIds = when (input.mode) {
            TrackerMapDisplayMode.SINGLE_SESSION -> TrackerMapViewModel.resolveStreamTargetIds(
                mode = input.mode,
                runtimeRunning = input.runtimeRunning,
                selectedTrackerId = input.selectedTrackerId,
                displayedTrackerId = input.displayedTrackerId,
                rosterTrackerIds = emptySet()
            )
            TrackerMapDisplayMode.GROUP_PLACEHOLDER -> {
                val groupIds = input.groupSelection.trackerIds
                val normalizedSelected = input.selectedTrackerId.trim()
                if (input.runtimeRunning && normalizedSelected.isNotEmpty()) {
                    groupIds - normalizedSelected
                } else {
                    groupIds
                }
            }
            TrackerMapDisplayMode.ALL_QUEUE -> TrackerMapViewModel.resolveStreamTargetIds(
                mode = input.mode,
                runtimeRunning = input.runtimeRunning,
                selectedTrackerId = input.selectedTrackerId,
                displayedTrackerId = input.displayedTrackerId,
                rosterTrackerIds = input.rosterTrackerIds
            )
        }
        return TrackerMapStreamTargetResult(
            streamTargetIds = streamIds,
            resolvedGroupId = input.groupSelection.groupId.orEmpty()
        )
    }
}
