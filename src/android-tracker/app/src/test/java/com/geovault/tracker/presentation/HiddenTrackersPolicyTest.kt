package com.geovault.tracker.presentation

import com.geovault.tracker.Group
import com.geovault.tracker.Tracker
import org.junit.Assert.assertEquals
import org.junit.Test

class HiddenTrackersPolicyTest {

    @Test
    fun buildItems_includesOnlyOwnedHiddenTrackersAndGroups_sortedByName() {
        val trackers = listOf(
            Tracker(id = "t1", name = "Zulu", color = null, settings = mapOf("hidden" to true), is_owner = true),
            Tracker(id = "t2", name = "Alpha", color = null, settings = mapOf("hidden" to true), is_owner = true),
            Tracker(id = "t3", name = "Bravo", color = null, settings = mapOf("hidden" to false), is_owner = true),
            Tracker(id = "t4", name = "Charlie", color = null, settings = mapOf("hidden" to true), is_owner = false),
        )
        val groups = listOf(
            Group(id = "g1", name = "Gamma", hidden = true, is_owner = true),
            Group(id = "g2", name = "Beta", hidden = true, is_owner = true),
            Group(id = "g3", name = "Delta", hidden = false, is_owner = true),
            Group(id = "g4", name = "Epsilon", hidden = true, is_owner = false),
        )

        val items = HiddenTrackersPolicy.buildItems(trackers, groups)

        assertEquals(listOf("Alpha", "Zulu", "Beta", "Gamma"), items.map { it.name })
        assertEquals(
            listOf(
                HiddenTrackerItemType.TRACKER,
                HiddenTrackerItemType.TRACKER,
                HiddenTrackerItemType.GROUP,
                HiddenTrackerItemType.GROUP
            ),
            items.map { it.type }
        )
    }
}
