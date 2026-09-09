package com.geovault.tracker.ui

import com.geovault.tracker.params.TrackerParamsRouteArgs
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

sealed class MapNavigation {
    data class List(val request: MapHostNavigationRequest) : MapNavigation()
    data class Params(val args: TrackerParamsRouteArgs) : MapNavigation()
}

data class MapHostNavigationRequest(
    val target: MapHostNavigationTarget,
    val trackerId: String? = null,
    val groupId: String? = null,
    // Safe default: do not trigger highlight/scroll unless explicitly requested.
    val focus: MapHostNavigationFocus = MapHostNavigationFocus.NONE,
)

data class TrackersHostNavigationRequest(
    val subTab: TrackersGroupsSubTab,
    val trackerId: String? = null,
    val groupId: String? = null,
    // Safe default: do not trigger highlight/scroll unless explicitly requested.
    val focus: MapHostNavigationFocus = MapHostNavigationFocus.NONE,
)

data class SharedHostNavigationRequest(
    val subTab: SharedSubTab,
    val trackerId: String? = null,
    val groupId: String? = null,
    // Safe default: do not trigger highlight/scroll unless explicitly requested.
    val focus: MapHostNavigationFocus = MapHostNavigationFocus.NONE,
)

internal object MapHostNavigationRequestResolver {
    fun forTrackers(
        state: TrackerMapUiState,
        selectedTrackerId: String = "",
    ): MapHostNavigationRequest {
        val preferredTrackerId = preferredTrackerId(state, selectedTrackerId)
        if (state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER && state.currentGroupId.isNotBlank()) {
            return MapHostNavigationRequest(
                target = MapHostNavigationTarget.GROUPS,
                trackerId = preferredTrackerId,
                groupId = state.currentGroupId,
                focus = focusForSelection(preferredTrackerId, state.currentGroupId),
            )
        }
        return MapHostNavigationRequest(
            target = MapHostNavigationTarget.TRACKERS,
            trackerId = preferredTrackerId,
            focus = focusForSelection(preferredTrackerId, null),
        )
    }

    fun forShared(
        state: TrackerMapUiState,
        selectedTrackerId: String = "",
    ): MapHostNavigationRequest {
        val preferred = preferredTrackerId(state, selectedTrackerId)
        return MapHostNavigationRequest(
            target = MapHostNavigationTarget.SHARED,
            trackerId = preferred,
            groupId = state.currentGroupId.takeIf { state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER },
            focus = focusForSelection(
                preferred,
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
                focus = focusForSelection(target.trackerId, target.groupId),
            )
            MapListNavigationDestination.GROUPS -> MapHostNavigationRequest(
                target = MapHostNavigationTarget.GROUPS,
                trackerId = target.trackerId,
                groupId = target.groupId,
                focus = focusForSelection(target.trackerId, target.groupId),
            )
            MapListNavigationDestination.SHARED -> MapHostNavigationRequest(
                target = MapHostNavigationTarget.SHARED,
                trackerId = target.trackerId,
                groupId = target.groupId,
                focus = focusForSelection(target.trackerId, target.groupId),
            )
        }
    }

    private fun preferredTrackerId(state: TrackerMapUiState, selectedTrackerId: String): String? {
        val displayedId = state.displayedTrackerId.trim()
        if (displayedId.isNotEmpty()) return displayedId
        return selectedTrackerId.trim().ifEmpty { null }
    }

    private fun focusForSelection(trackerId: String?, groupId: String?): MapHostNavigationFocus {
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
