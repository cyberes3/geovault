package com.geovault.tracker.presentation

data class TrackerMapStreamSeedInput(
    val mode: TrackerMapDisplayMode,
    val runtimeRunning: Boolean,
    val selectedTrackerId: String,
    val displayedTrackerId: String,
    val rosterTrackerIds: Collection<String>,
    val groupSelection: TrackerMapGroupModeSelection,
)

data class TrackerMapTrailSeedInput(
    val mode: TrackerMapDisplayMode,
    val runtimeRunning: Boolean,
    val activeTrackerId: String,
    val sessionVisibleBoundaryId: Long,
    val rosterTrackerIds: Collection<String>,
    val groupSelection: TrackerMapGroupModeSelection,
    val renderMetadataSignature: String = "",
)

object TrackerMapReloadSeedPolicy {
    fun streamSeed(input: TrackerMapStreamSeedInput): String {
        val trackerRosterSignature = normalizedIdsSignature(input.rosterTrackerIds)
        val groupModeSignature = groupSelectionSignature(input.groupSelection)
        return "${input.mode}|${input.runtimeRunning}|${input.selectedTrackerId}|${input.displayedTrackerId}|$trackerRosterSignature|$groupModeSignature"
    }

    fun trailSeed(input: TrackerMapTrailSeedInput): String {
        val rosterSignature = normalizedIdsSignature(input.rosterTrackerIds)
        val groupModeSignature = groupSelectionSignature(input.groupSelection)
        return "${input.mode}|${input.runtimeRunning}|${input.activeTrackerId}|${input.sessionVisibleBoundaryId}|$rosterSignature|$groupModeSignature|${input.renderMetadataSignature}"
    }

    private fun normalizedIdsSignature(ids: Collection<String>): String {
        return ids
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .sorted()
            .joinToString(separator = ",")
    }

    private fun groupSelectionSignature(selection: TrackerMapGroupModeSelection): String {
        val trackerIds = selection.trackerIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .sorted()
            .joinToString(separator = ",")
        return "${selection.groupId.orEmpty()}|$trackerIds"
    }
}
