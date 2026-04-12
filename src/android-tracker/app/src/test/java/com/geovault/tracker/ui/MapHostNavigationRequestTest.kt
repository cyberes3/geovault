package com.geovault.tracker.ui

import com.geovault.tracker.presentation.SharedSubTab
import com.geovault.tracker.presentation.TrackersGroupsSubTab
import org.junit.Assert.assertEquals
import org.junit.Test

class MapHostNavigationRequestTest {

    @Test
    fun requestDefaultsDoNotAskForFocus() {
        val mapRequest = MapHostNavigationRequest(target = MapHostNavigationTarget.TRACKERS)
        val trackersRequest = TrackersHostNavigationRequest(subTab = TrackersGroupsSubTab.TRACKERS)
        val sharedRequest = SharedHostNavigationRequest(subTab = SharedSubTab.SHARED)

        assertEquals(MapHostNavigationFocus.NONE, mapRequest.focus)
        assertEquals(MapHostNavigationFocus.NONE, trackersRequest.focus)
        assertEquals(MapHostNavigationFocus.NONE, sharedRequest.focus)
    }

    @Test
    fun toTrackersHostNavigationRequest_groupsTargetUsesGroupsTab() {
        val request = MapHostNavigationRequest(
            target = MapHostNavigationTarget.GROUPS,
            trackerId = "t1",
            groupId = "g1",
            focus = MapHostNavigationFocus.SCROLL_TO_ITEM,
        )

        val resolved = request.toTrackersHostNavigationRequest()

        assertEquals(TrackersGroupsSubTab.GROUPS, resolved.subTab)
        assertEquals("t1", resolved.trackerId)
        assertEquals("g1", resolved.groupId)
        assertEquals(MapHostNavigationFocus.SCROLL_TO_ITEM, resolved.focus)
    }

    @Test
    fun toSharedHostNavigationRequest_normalizesToSharedSubTab() {
        val request = MapHostNavigationRequest(
            target = MapHostNavigationTarget.SHARED,
            trackerId = "t42",
            groupId = "g42",
            focus = MapHostNavigationFocus.SCROLL_TO_ITEM,
        )

        val resolved = request.toSharedHostNavigationRequest()

        assertEquals(SharedSubTab.SHARED, resolved.subTab)
        assertEquals("t42", resolved.trackerId)
        assertEquals("g42", resolved.groupId)
        assertEquals(MapHostNavigationFocus.SCROLL_TO_ITEM, resolved.focus)
    }
}
