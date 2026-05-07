package com.geovault.tracker.presentation

import com.geovault.tracker.policy.StreamingTargetPolicy

enum class TrackerMapTrailSource {
    SINGLE_SERVER,
    MULTI_SERVER,
    SINGLE_QUEUE,
}

data class TrackerMapTrailReloadInput(
    val mode: TrackerMapDisplayMode,
    val runtimeRunning: Boolean,
    val selectedTrackerId: String,
    val locallyRecordedTrackerId: String = "",
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
        val locallyRecorded = input.locallyRecordedTrackerId.trim().ifBlank {
            selected.takeIf { input.runtimeRunning }.orEmpty()
        }
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
                overlayTrackerId = locallyRecorded.takeIf { input.runtimeRunning && active == locallyRecorded },
                activeTrackerId = active
            )
        }
        if (input.mode == TrackerMapDisplayMode.ALL_QUEUE) {
            return TrackerMapTrailReloadPlan(
                source = TrackerMapTrailSource.MULTI_SERVER,
                trackerIds = serverHistoryTrackerIds(rosterIds),
                overlayTrackerId = locallyRecorded.takeIf { input.runtimeRunning && it.isNotEmpty() },
                activeTrackerId = active
            )
        }
        if (input.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
            return TrackerMapTrailReloadPlan(
                source = TrackerMapTrailSource.MULTI_SERVER,
                trackerIds = serverHistoryTrackerIds(groupIds),
                overlayTrackerId = locallyRecorded.takeIf {
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

    /**
     * GROUP / ALL-QUEUE TRAIL HISTORY: load server history for every visible tracker, including
     * the user's own selected and locally-recorded tracker. Selected/locally-recorded exclusion
     * is a STREAMING decision (a subscription targeting concern), not a HISTORY decision; the
     * user expects to see their own trail alongside the rest of the group/roster. This matches
     * SINGLE_SESSION, which already loads server history for the displayed tracker even when
     * that tracker is locally recorded — the local overlay is applied on top via the trail merge
     * policy, not as a substitute for history.
     */
    private fun serverHistoryTrackerIds(requestedTrackerIds: Set<String>): Set<String> {
        return StreamingTargetPolicy.normalizeTrackerIds(requestedTrackerIds)
    }
}
