package com.geovault.tracker.presentation

import com.geovault.tracker.MapVisibilityResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapVisibilityToggleTest {

    @Test
    fun toggleTracker_addsAndRemovesHiddenId() {
        val empty = MapVisibilityResponse()
        val add = toggleTrackerInVisibility(empty, "t1")
        assertEquals(listOf("t1"), add.hidden_track_ids)
        assertEquals(emptyList<String>(), add.hidden_group_ids)

        val current = MapVisibilityResponse(hidden_track_ids = listOf("t1", "t2"))
        val remove = toggleTrackerInVisibility(current, "t1")
        assertTrue((remove.hidden_track_ids ?: emptyList()).contains("t2"))
        assertFalse((remove.hidden_track_ids ?: emptyList()).contains("t1"))
    }

    @Test
    fun toggleGroup_preservesTrackHiddenList() {
        val current = MapVisibilityResponse(
            hidden_track_ids = listOf("a"),
            hidden_group_ids = listOf("g1"),
        )
        val toggled = toggleGroupInVisibility(current, "g2")
        assertEquals(listOf("a"), toggled.hidden_track_ids)
        assertEquals(listOf("g1", "g2"), toggled.hidden_group_ids?.sorted())
    }
}
