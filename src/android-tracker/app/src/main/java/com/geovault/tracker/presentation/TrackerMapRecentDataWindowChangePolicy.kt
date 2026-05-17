package com.geovault.tracker.presentation

data class RecentDataWindowChangeAction(
    val reprojectImmediately: Boolean,
    val serverRefreshTrackerIds: Set<String>,
    val invalidateGeometryCache: Set<String>,
)

/**
 * Resolves how the map should react when one or more trackers' `recent_data_window`
 * settings change. Server geometry is authoritative for persisted history; client
 * re-projection covers live overlays until the fetch lands.
 */
object TrackerMapRecentDataWindowChangePolicy {

    fun resolve(
        changedTrackerIds: Set<String>,
        mode: TrackerMapDisplayMode,
        displayedTrackerId: String,
        selectedTrackerId: String,
        rosterTrackerIds: Set<String>,
        groupTrackerIds: Set<String>,
    ): RecentDataWindowChangeAction {
        if (changedTrackerIds.isEmpty()) {
            return RecentDataWindowChangeAction(
                reprojectImmediately = false,
                serverRefreshTrackerIds = emptySet(),
                invalidateGeometryCache = emptySet(),
            )
        }
        val visibleContextIds = visibleTrackerIdsInMapContext(
            mode = mode,
            displayedTrackerId = displayedTrackerId,
            selectedTrackerId = selectedTrackerId,
            rosterTrackerIds = rosterTrackerIds,
            groupTrackerIds = groupTrackerIds,
        )
        val serverRefresh = changedTrackerIds
            .map { it.trim() }
            .filter { it.isNotEmpty() && it in visibleContextIds }
            .toSet()
        return RecentDataWindowChangeAction(
            reprojectImmediately = true,
            serverRefreshTrackerIds = serverRefresh,
            invalidateGeometryCache = serverRefresh,
        )
    }

    internal fun visibleTrackerIdsInMapContext(
        mode: TrackerMapDisplayMode,
        displayedTrackerId: String,
        selectedTrackerId: String,
        rosterTrackerIds: Set<String>,
        groupTrackerIds: Set<String>,
    ): Set<String> {
        return when (mode) {
            TrackerMapDisplayMode.SINGLE_SESSION -> {
                val displayed = displayedTrackerId.trim()
                val selected = selectedTrackerId.trim()
                when {
                    displayed.isNotEmpty() -> setOf(displayed)
                    selected.isNotEmpty() -> setOf(selected)
                    else -> emptySet()
                }
            }
            TrackerMapDisplayMode.GROUP_PLACEHOLDER -> {
                groupTrackerIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            }
            TrackerMapDisplayMode.ALL_QUEUE -> {
                rosterTrackerIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            }
        }
    }
}
