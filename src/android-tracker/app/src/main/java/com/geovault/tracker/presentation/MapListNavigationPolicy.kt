package com.geovault.tracker.presentation

enum class MapListNavigationDestination {
    TRACKERS,
    GROUPS,
    SHARED,
}

data class MapListNavigationTarget(
    val destination: MapListNavigationDestination,
    val trackerId: String? = null,
    val groupId: String? = null,
)

object MapListNavigationPolicy {
    fun resolve(
        mode: TrackerMapDisplayMode,
        currentGroupId: String,
        preferredTrackerId: String?,
        isCurrentGroupOwned: Boolean?,
        isPreferredTrackerOwned: Boolean?,
    ): MapListNavigationTarget {
        val normalizedGroupId = currentGroupId.trim()
        val normalizedTrackerId = preferredTrackerId?.trim().orEmpty().ifBlank { null }
        if (mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER && normalizedGroupId.isNotEmpty()) {
            return if (isCurrentGroupOwned == true) {
                MapListNavigationTarget(
                    destination = MapListNavigationDestination.GROUPS,
                    trackerId = normalizedTrackerId,
                    groupId = normalizedGroupId,
                )
            } else {
                MapListNavigationTarget(
                    destination = MapListNavigationDestination.SHARED,
                    trackerId = normalizedTrackerId,
                    groupId = normalizedGroupId,
                )
            }
        }
        if (normalizedTrackerId != null && isPreferredTrackerOwned == false) {
            return MapListNavigationTarget(
                destination = MapListNavigationDestination.SHARED,
                trackerId = normalizedTrackerId,
            )
        }
        return MapListNavigationTarget(
            destination = MapListNavigationDestination.TRACKERS,
            trackerId = normalizedTrackerId,
        )
    }
}
