package com.geovault.tracker.presentation

import com.geovault.tracker.ui.MapHostNavigationFocus
import com.geovault.tracker.ui.TrackersHostNavigationRequest

data class TrackersListPositioningInput(
    val activeSubTab: TrackersGroupsSubTab,
    val isLoading: Boolean,
    val isPullRefreshing: Boolean,
    val hasInitializedTrackersTop: Boolean,
    val navigationRequest: TrackersHostNavigationRequest?,
)

sealed interface TrackersListPositioningAction {
    data object NoOp : TrackersListPositioningAction
    data object ConsumeWithoutScroll : TrackersListPositioningAction
    data object ScrollToTopOnce : TrackersListPositioningAction
    data class ScrollToTracker(val trackerId: String) : TrackersListPositioningAction
    data class ScrollToGroup(val groupId: String) : TrackersListPositioningAction
    data class ScrollToGroupContainingTracker(val trackerId: String) : TrackersListPositioningAction
}

object TrackersListPositioningPolicy {
    fun resolve(input: TrackersListPositioningInput): TrackersListPositioningAction {
        val request = input.navigationRequest
        if (request != null) {
            if (request.subTab != input.activeSubTab) return TrackersListPositioningAction.NoOp
            if (request.focus != MapHostNavigationFocus.SCROLL_TO_ITEM) {
                return TrackersListPositioningAction.ConsumeWithoutScroll
            }
            val trackerId = request.trackerId.orEmpty().trim()
            val groupId = request.groupId.orEmpty().trim()
            return when (input.activeSubTab) {
                TrackersGroupsSubTab.TRACKERS -> {
                    if (trackerId.isNotEmpty()) {
                        TrackersListPositioningAction.ScrollToTracker(trackerId)
                    } else {
                        TrackersListPositioningAction.ConsumeWithoutScroll
                    }
                }
                TrackersGroupsSubTab.GROUPS -> {
                    when {
                        groupId.isNotEmpty() -> TrackersListPositioningAction.ScrollToGroup(groupId)
                        trackerId.isNotEmpty() -> {
                            TrackersListPositioningAction.ScrollToGroupContainingTracker(trackerId)
                        }
                        else -> TrackersListPositioningAction.ConsumeWithoutScroll
                    }
                }
            }
        }
        if (
            input.activeSubTab == TrackersGroupsSubTab.TRACKERS &&
            !input.hasInitializedTrackersTop &&
            !input.isLoading &&
            !input.isPullRefreshing
        ) {
            return TrackersListPositioningAction.ScrollToTopOnce
        }
        return TrackersListPositioningAction.NoOp
    }
}
