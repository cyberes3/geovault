package com.geovault.tracker.presentation

import com.geovault.tracker.policy.StreamingTargetPolicy
import com.geovault.tracker.policy.StreamingTargetPolicyInput

enum class TrackerMapTrailSource {
    SINGLE_SERVER,
    MULTI_SERVER,
    SINGLE_QUEUE,
}

data class TrackerMapTrailReloadInput(
    val mode: TrackerMapDisplayMode,
    val runtimeRunning: Boolean,
    val selectedTrackerId: String,
    val activeTrackerId: String,
    val rosterTrackerIds: Set<String>,
    val groupSelection: TrackerMapGroupModeSelection,
)

data class TrackerMapTrailReloadPlan(
    val source: TrackerMapTrailSource,
    val singleTrackerId: String = "",
    val trackerIds: Set<String> = emptySet(),
    val overlayTrackerId: String? = null,
    val activeTrackerId: String = "",
    val resolvedGroupId: String = "",
)

object TrackerMapTrailReloadCoordinator {
    fun resolvePlan(input: TrackerMapTrailReloadInput): TrackerMapTrailReloadPlan {
        val active = input.activeTrackerId.trim()
        val selected = input.selectedTrackerId.trim()
        val rosterIds = input.rosterTrackerIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val groupIds = input.groupSelection.trackerIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (input.mode == TrackerMapDisplayMode.SINGLE_SESSION && active.isNotEmpty()) {
            return TrackerMapTrailReloadPlan(
                source = TrackerMapTrailSource.SINGLE_SERVER,
                singleTrackerId = active,
                overlayTrackerId = active.takeIf { input.runtimeRunning && active == selected },
                activeTrackerId = active
            )
        }
        if (input.mode == TrackerMapDisplayMode.ALL_QUEUE) {
            return TrackerMapTrailReloadPlan(
                source = TrackerMapTrailSource.MULTI_SERVER,
                trackerIds = serverHistoryTrackerIds(rosterIds, selected),
                overlayTrackerId = active.takeIf { input.runtimeRunning && it.isNotEmpty() },
                activeTrackerId = active
            )
        }
        if (input.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
            return TrackerMapTrailReloadPlan(
                source = TrackerMapTrailSource.MULTI_SERVER,
                trackerIds = serverHistoryTrackerIds(groupIds, selected),
                overlayTrackerId = active.takeIf {
                    input.runtimeRunning && it.isNotEmpty() && it in groupIds
                },
                activeTrackerId = active,
                resolvedGroupId = input.groupSelection.groupId.orEmpty()
            )
        }
        return TrackerMapTrailReloadPlan(
            source = TrackerMapTrailSource.SINGLE_QUEUE,
            activeTrackerId = active
        )
    }

    private fun serverHistoryTrackerIds(requestedTrackerIds: Set<String>, selectedTrackerId: String): Set<String> {
        return StreamingTargetPolicy.remoteSubscriptionTargets(
            StreamingTargetPolicyInput(
                requestedTrackerIds = requestedTrackerIds,
                selectedTrackerId = selectedTrackerId,
            )
        )
    }
}
