package com.geovault.tracker.presentation

import com.geovault.tracker.Group
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.Tracker
import org.junit.Assert.assertEquals
import org.junit.Test

class HiddenMapItemsPolicyTest {

    @Test
    fun buildHiddenItems_mapsTrackerAndGroupNames() {
        val visibility = MapVisibilityResponse(
            hidden_track_ids = listOf("t2"),
            hidden_group_ids = listOf("g1")
        )
        val trackers = listOf(
            Tracker(id = "t1", name = "Alpha", color = null),
            Tracker(id = "t2", name = "Bravo", color = null),
        )
        val groups = listOf(
            Group(id = "g1", name = "Field Team"),
        )

        val hidden = HiddenMapItemsPolicy.buildHiddenItems(
            mapVisibility = visibility,
            trackers = trackers,
            groups = groups
        )

        assertEquals(2, hidden.size)
        assertEquals(HiddenMapItemType.GROUP, hidden[0].type)
        assertEquals("Field Team", hidden[0].name)
        assertEquals(HiddenMapItemType.TRACKER, hidden[1].type)
        assertEquals("Bravo", hidden[1].name)
    }

    @Test
    fun buildHiddenItems_fallsBackToIdWhenNameUnavailable() {
        val visibility = MapVisibilityResponse(
            hidden_track_ids = listOf("missing-track"),
            hidden_group_ids = listOf("missing-group")
        )

        val hidden = HiddenMapItemsPolicy.buildHiddenItems(
            mapVisibility = visibility,
            trackers = emptyList(),
            groups = emptyList()
        )

        assertEquals("missing-group", hidden[0].name)
        assertEquals("missing-track", hidden[1].name)
    }

    @Test
    fun visibleTrackerIdsForMap_excludesHiddenAndOwnerHiddenTrackers() {
        val visibility = MapVisibilityResponse(hidden_track_ids = listOf("t2"))
        val trackers = listOf(
            Tracker(id = "t1", name = "Alpha", color = null),
            Tracker(id = "t2", name = "Bravo", color = null),
            Tracker(
                id = "t3",
                name = "Hidden Owner",
                color = null,
                is_owner = true,
                settings = mapOf("hidden" to true)
            ),
        )

        val visible = HiddenMapItemsPolicy.visibleTrackerIdsForMap(
            rosterTrackerIds = listOf("t1", "t2", "t3", " "),
            mapVisibility = visibility,
            trackers = trackers,
        )

        assertEquals(setOf("t1"), visible)
    }
}
