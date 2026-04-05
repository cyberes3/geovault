package com.geovault.tracker.ui

import com.geovault.tracker.presentation.SharedSubTab
import com.geovault.tracker.presentation.TrackersGroupsSubTab
import org.junit.Assert.assertEquals
import org.junit.Test

class MapHostNavigationRequestTest {

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
    }
}
