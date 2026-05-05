package com.geovault.tracker.presentation

data class TrackerMapStreamingDecisionInput(
    val mode: TrackerMapDisplayMode,
    val streamTargetIds: Set<String>,
    val displayedTrackerId: String,
    val displayedTrackerName: String,
    val selectedTrackerId: String,
    /** When true and [selectedTrackerId] is non-blank, multi-context streaming excludes the selected id (local GPS). */
    val trackingRunning: Boolean = false,
)

sealed class TrackerMapStreamingCommand {
    data class Start(val trackerIds: Set<String>, val trackerName: String?) : TrackerMapStreamingCommand()
    data object Stop : TrackerMapStreamingCommand()
    data object NoOp : TrackerMapStreamingCommand()
}

object TrackerMapStreamingCoordinator {
    fun resolve(input: TrackerMapStreamingDecisionInput): TrackerMapStreamingCommand {
        return if (input.mode == TrackerMapDisplayMode.SINGLE_SESSION) {
            resolveSingleSession(input)
        } else {
            resolveMultiContext(input)
        }
    }

    private fun resolveSingleSession(input: TrackerMapStreamingDecisionInput): TrackerMapStreamingCommand {
        val id = input.displayedTrackerId.trim()
        if (id.isEmpty()) {
            // Keep no-op behavior while single-track context is still resolving.
            return TrackerMapStreamingCommand.NoOp
        }
        if (input.trackingRunning && id == input.selectedTrackerId.trim() && input.selectedTrackerId.isNotBlank()) {
            return TrackerMapStreamingCommand.Stop
        }
        return TrackerMapStreamingCommand.Start(
            trackerIds = setOf(id),
            trackerName = input.displayedTrackerName.trim().ifBlank { null }
        )
    }

    private fun resolveMultiContext(input: TrackerMapStreamingDecisionInput): TrackerMapStreamingCommand {
        val localTrackerId = input.selectedTrackerId.trim().takeIf { input.trackingRunning && it.isNotEmpty() }
        val ids = input.streamTargetIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it == localTrackerId }
            .toSet()
        if (ids.isEmpty()) return TrackerMapStreamingCommand.Stop
        val trackerName = if (ids.size == 1) {
            input.displayedTrackerName.trim().ifBlank { null }
        } else {
            null
        }
        return TrackerMapStreamingCommand.Start(
            trackerIds = ids,
            trackerName = trackerName
        )
    }
}
