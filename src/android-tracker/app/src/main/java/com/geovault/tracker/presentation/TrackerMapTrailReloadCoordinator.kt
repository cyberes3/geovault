package com.geovault.tracker.presentation

enum class TrackerMapTrailSource {
    SINGLE_SERVER,
    MULTI_SERVER,
    SINGLE_QUEUE,
}

data class TrackerMapTrailReloadInput(
    val mode: TrackerMapDisplayMode,
    val runtimeRunning: Boolean,
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
        val rosterIds = input.rosterTrackerIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val groupIds = input.groupSelection.trackerIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (!input.runtimeRunning &&
            input.mode == TrackerMapDisplayMode.SINGLE_SESSION &&
            active.isNotEmpty()
        ) {
            return TrackerMapTrailReloadPlan(
                source = TrackerMapTrailSource.SINGLE_SERVER,
                singleTrackerId = active,
                activeTrackerId = active
            )
        }
        if (input.mode == TrackerMapDisplayMode.ALL_QUEUE) {
            return TrackerMapTrailReloadPlan(
                source = TrackerMapTrailSource.MULTI_SERVER,
                trackerIds = rosterIds,
                overlayTrackerId = active.takeIf { input.runtimeRunning && it.isNotEmpty() },
                activeTrackerId = active
            )
        }
        if (input.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
            return TrackerMapTrailReloadPlan(
                source = TrackerMapTrailSource.MULTI_SERVER,
                trackerIds = groupIds,
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
}
