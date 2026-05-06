package com.geovault.tracker.presentation

import com.geovault.tracker.policy.StreamingTargetPolicy
import com.geovault.tracker.policy.StreamingTargetPolicyInput

data class TrackerMapStreamingDecisionInput(
    val mode: TrackerMapDisplayMode,
    val streamTargetIds: Set<String>,
    val displayedTrackerId: String,
    val displayedTrackerName: String,
    val selectedTrackerId: String,
    /** The selected tracker is local to this device and must never be subscribed as a remote stream. */
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
        val ids = StreamingTargetPolicy.remoteSubscriptionTargets(
            StreamingTargetPolicyInput(
                requestedTrackerIds = setOf(id),
                selectedTrackerId = input.selectedTrackerId,
            )
        )
        if (ids.isEmpty()) {
            return TrackerMapStreamingCommand.Stop
        }
        return TrackerMapStreamingCommand.Start(
            trackerIds = ids,
            trackerName = input.displayedTrackerName.trim().ifBlank { null }
        )
    }

    private fun resolveMultiContext(input: TrackerMapStreamingDecisionInput): TrackerMapStreamingCommand {
        val ids = StreamingTargetPolicy.remoteSubscriptionTargets(
            StreamingTargetPolicyInput(
                requestedTrackerIds = input.streamTargetIds,
                selectedTrackerId = input.selectedTrackerId,
            )
        )
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
