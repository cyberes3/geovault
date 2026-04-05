package com.geovault.tracker.ui

import com.geovault.tracker.presentation.SharedSubTab
import com.geovault.tracker.presentation.MapListNavigationDestination
import com.geovault.tracker.presentation.MapListNavigationTarget
import com.geovault.tracker.presentation.TrackerMapDisplayMode
import com.geovault.tracker.presentation.TrackerMapUiState
import com.geovault.tracker.presentation.TrackersGroupsSubTab

enum class MapHostNavigationTarget {
    TRACKERS,
    GROUPS,
    SHARED,
}

enum class MapHostNavigationFocus {
    NONE,
    SCROLL_TO_ITEM,
}

data class MapHostNavigationRequest(
    val target: MapHostNavigationTarget,
    val trackerId: String? = null,
    val groupId: String? = null,
    val focus: MapHostNavigationFocus = MapHostNavigationFocus.SCROLL_TO_ITEM,
)

data class TrackersHostNavigationRequest(
    val subTab: TrackersGroupsSubTab,
    val trackerId: String? = null,
    val groupId: String? = null,
    val focus: MapHostNavigationFocus = MapHostNavigationFocus.SCROLL_TO_ITEM,
)

data class SharedHostNavigationRequest(
    val subTab: SharedSubTab,
    val trackerId: String? = null,
    val groupId: String? = null,
    val focus: MapHostNavigationFocus = MapHostNavigationFocus.SCROLL_TO_ITEM,
)

object MapHostNavigationRequestResolver {
    fun forTrackers(state: TrackerMapUiState): MapHostNavigationRequest {
        val preferredTrackerId = preferredTrackerId(state)
        if (state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER && state.currentGroupId.isNotBlank()) {
            return MapHostNavigationRequest(
                target = MapHostNavigationTarget.GROUPS,
                trackerId = preferredTrackerId,
                groupId = state.currentGroupId,
                focus = resolveFocus(preferredTrackerId, state.currentGroupId),
            )
        }
        return MapHostNavigationRequest(
            target = MapHostNavigationTarget.TRACKERS,
            trackerId = preferredTrackerId,
            focus = resolveFocus(preferredTrackerId, null),
        )
    }

    fun forShared(state: TrackerMapUiState): MapHostNavigationRequest {
        return MapHostNavigationRequest(
            target = MapHostNavigationTarget.SHARED,
            trackerId = preferredTrackerId(state),
            groupId = state.currentGroupId.takeIf { state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER },
            focus = resolveFocus(
                preferredTrackerId(state),
                state.currentGroupId.takeIf { state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER }
            ),
        )
    }

    fun fromListNavigationTarget(target: MapListNavigationTarget): MapHostNavigationRequest {
        return when (target.destination) {
            MapListNavigationDestination.TRACKERS -> MapHostNavigationRequest(
                target = MapHostNavigationTarget.TRACKERS,
                trackerId = target.trackerId,
                groupId = target.groupId,
                focus = resolveFocus(target.trackerId, target.groupId),
            )
            MapListNavigationDestination.GROUPS -> MapHostNavigationRequest(
                target = MapHostNavigationTarget.GROUPS,
                trackerId = target.trackerId,
                groupId = target.groupId,
                focus = resolveFocus(target.trackerId, target.groupId),
            )
            MapListNavigationDestination.SHARED -> MapHostNavigationRequest(
                target = MapHostNavigationTarget.SHARED,
                trackerId = target.trackerId,
                groupId = target.groupId,
                focus = resolveFocus(target.trackerId, target.groupId),
            )
        }
    }

    private fun preferredTrackerId(state: TrackerMapUiState): String? {
        val displayedId = state.displayedTrackerId.trim()
        if (displayedId.isNotEmpty()) return displayedId
        val selectedId = state.runtime.selectedTrackerId.trim()
        return selectedId.ifEmpty { null }
    }

    private fun resolveFocus(trackerId: String?, groupId: String?): MapHostNavigationFocus {
        return if (trackerId.isNullOrBlank() && groupId.isNullOrBlank()) {
            MapHostNavigationFocus.NONE
        } else {
            MapHostNavigationFocus.SCROLL_TO_ITEM
        }
    }
}

fun MapHostNavigationRequest.toTrackersHostNavigationRequest(): TrackersHostNavigationRequest {
    val subTab = when (target) {
        MapHostNavigationTarget.GROUPS -> TrackersGroupsSubTab.GROUPS
        MapHostNavigationTarget.TRACKERS,
        MapHostNavigationTarget.SHARED -> TrackersGroupsSubTab.TRACKERS
    }
    return TrackersHostNavigationRequest(
        subTab = subTab,
        trackerId = trackerId,
        groupId = groupId,
        focus = focus,
    )
}

fun MapHostNavigationRequest.toSharedHostNavigationRequest(): SharedHostNavigationRequest {
    // Shared map navigation always lands on the combined Shared surface list where both
    // group and standalone tracker rows are resolved.
    return SharedHostNavigationRequest(
        subTab = SharedSubTab.SHARED,
        trackerId = trackerId,
        groupId = groupId,
        focus = focus,
    )
}
