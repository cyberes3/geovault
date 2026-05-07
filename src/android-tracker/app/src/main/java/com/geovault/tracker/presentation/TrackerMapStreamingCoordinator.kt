package com.geovault.tracker.presentation

import com.geovault.tracker.policy.StreamingTargetPolicy

data class TrackerMapStreamingDecisionInput(
    val mode: TrackerMapDisplayMode,
    val streamTargetIds: Set<String>,
    val displayedTrackerId: String,
    val displayedTrackerName: String,
)

sealed class TrackerMapStreamingCommand {
    data class Start(val trackerIds: Set<String>, val trackerName: String?) : TrackerMapStreamingCommand()
    data object Stop : TrackerMapStreamingCommand()
    data object NoOp : TrackerMapStreamingCommand()
}

object TrackerMapStreamingCoordinator {
    /**
     * STREAMING-COORDINATOR (single source of truth): the projected `streamTargetIds` already
     * encodes every per-mode targeting decision (SINGLE on selected -> empty, SINGLE on other
     * -> {other}, GROUP -> group minus locally-recorded, ALL_QUEUE -> roster minus locally-
     * recorded). We do NOT re-derive or re-filter the set here. The only special case is
     * SINGLE_SESSION with no displayed id at all: that is "single context still resolving"
     * and must NoOp instead of Stop, otherwise we'd kill an in-flight params subscription.
     */
    fun resolve(input: TrackerMapStreamingDecisionInput): TrackerMapStreamingCommand {
        if (input.mode == TrackerMapDisplayMode.SINGLE_SESSION &&
            input.displayedTrackerId.trim().isEmpty()
        ) {
            return TrackerMapStreamingCommand.NoOp
        }
        val ids = StreamingTargetPolicy.normalizeTrackerIds(input.streamTargetIds)
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
