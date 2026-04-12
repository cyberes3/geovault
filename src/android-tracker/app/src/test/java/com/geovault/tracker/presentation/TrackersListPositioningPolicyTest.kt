package com.geovault.tracker.presentation

import com.geovault.tracker.ui.MapHostNavigationFocus
import com.geovault.tracker.ui.TrackersHostNavigationRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackersListPositioningPolicyTest {

    @Test
    fun resolve_scrollToTopOnce_whenTrackersReadyAndNoNavigation() {
        val action = TrackersListPositioningPolicy.resolve(
            TrackersListPositioningInput(
                activeSubTab = TrackersGroupsSubTab.TRACKERS,
                isLoading = false,
                isPullRefreshing = false,
                hasInitializedTrackersTop = false,
                navigationRequest = null,
            )
        )

        assertEquals(TrackersListPositioningAction.ScrollToTopOnce, action)
    }

    @Test
    fun resolve_noOp_whenNavigationForDifferentTab() {
        val action = TrackersListPositioningPolicy.resolve(
            TrackersListPositioningInput(
                activeSubTab = TrackersGroupsSubTab.TRACKERS,
                isLoading = false,
                isPullRefreshing = false,
                hasInitializedTrackersTop = false,
                navigationRequest = TrackersHostNavigationRequest(
                    subTab = TrackersGroupsSubTab.GROUPS,
                    trackerId = "t1",
                    focus = MapHostNavigationFocus.SCROLL_TO_ITEM,
                ),
            )
        )

        assertEquals(TrackersListPositioningAction.NoOp, action)
    }

    @Test
    fun resolve_scrollToTracker_whenTrackersNavigationTargetsTracker() {
        val action = TrackersListPositioningPolicy.resolve(
            TrackersListPositioningInput(
                activeSubTab = TrackersGroupsSubTab.TRACKERS,
                isLoading = false,
                isPullRefreshing = false,
                hasInitializedTrackersTop = false,
                navigationRequest = TrackersHostNavigationRequest(
                    subTab = TrackersGroupsSubTab.TRACKERS,
                    trackerId = "t1",
                    focus = MapHostNavigationFocus.SCROLL_TO_ITEM,
                ),
            )
        )

        assertEquals(TrackersListPositioningAction.ScrollToTracker("t1"), action)
    }

    @Test
    fun resolve_scrollToGroup_whenGroupsNavigationTargetsGroup() {
        val action = TrackersListPositioningPolicy.resolve(
            TrackersListPositioningInput(
                activeSubTab = TrackersGroupsSubTab.GROUPS,
                isLoading = false,
                isPullRefreshing = false,
                hasInitializedTrackersTop = false,
                navigationRequest = TrackersHostNavigationRequest(
                    subTab = TrackersGroupsSubTab.GROUPS,
                    groupId = "g1",
                    focus = MapHostNavigationFocus.SCROLL_TO_ITEM,
                ),
            )
        )

        assertEquals(TrackersListPositioningAction.ScrollToGroup("g1"), action)
    }

    @Test
    fun resolve_scrollToGroupContainingTracker_whenGroupsNavigationTargetsTracker() {
        val action = TrackersListPositioningPolicy.resolve(
            TrackersListPositioningInput(
                activeSubTab = TrackersGroupsSubTab.GROUPS,
                isLoading = false,
                isPullRefreshing = false,
                hasInitializedTrackersTop = false,
                navigationRequest = TrackersHostNavigationRequest(
                    subTab = TrackersGroupsSubTab.GROUPS,
                    trackerId = "t5",
                    focus = MapHostNavigationFocus.SCROLL_TO_ITEM,
                ),
            )
        )

        assertEquals(TrackersListPositioningAction.ScrollToGroupContainingTracker("t5"), action)
    }

    @Test
    fun resolve_consumeWithoutScroll_whenNavigationFocusIsNone() {
        val action = TrackersListPositioningPolicy.resolve(
            TrackersListPositioningInput(
                activeSubTab = TrackersGroupsSubTab.TRACKERS,
                isLoading = false,
                isPullRefreshing = false,
                hasInitializedTrackersTop = false,
                navigationRequest = TrackersHostNavigationRequest(
                    subTab = TrackersGroupsSubTab.TRACKERS,
                    trackerId = "t1",
                    focus = MapHostNavigationFocus.NONE,
                ),
            )
        )

        assertEquals(TrackersListPositioningAction.ConsumeWithoutScroll, action)
    }
}
