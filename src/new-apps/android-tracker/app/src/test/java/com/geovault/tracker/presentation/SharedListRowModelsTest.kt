package com.geovault.tracker.presentation

import com.geovault.tracker.Group
import com.geovault.tracker.Tracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedListRowModelsTest {

    @Test
    fun toSharedListRows_buildsTrackerRowWithNormalizedMetadata() {
        val items = listOf(
            SharedSurfaceItem.TrackerItem(
                Tracker(
                    id = "t1",
                    name = "Tracker 1",
                    color = "#00ff00",
                    is_owner = false,
                    visibility = "shared",
                    owner_email = "owner@example.com",
                    last_point = listOf(12.3, 45.6, 1_700_000_000.0),
                )
            )
        )

        val rows = items.toSharedListRows(selectedTrackerId = "t1")
        val row = rows.single() as SharedListRowModel.TrackerRow

        assertEquals("s-t-t1", row.key)
        assertTrue(row.isSelected)
        assertTrue(row.canOpenMap)
        assertTrue(row.canEdit)
        assertEquals("owner@example.com", row.ownerEmail)
        assertEquals(45.6, row.latitude ?: 0.0, 0.0)
        assertEquals(12.3, row.longitude ?: 0.0, 0.0)
    }

    @Test
    fun toSharedListRows_buildsGroupRowWithDistinctTrackerCount() {
        val items = listOf(
            SharedSurfaceItem.GroupItem(
                Group(
                    id = "g1",
                    name = "Group 1",
                    is_owner = false,
                    visibility = "shared",
                    is_accepted = true,
                    owner_email = "group-owner@example.com",
                    track_ids = listOf("a", "a", "b", " "),
                )
            )
        )

        val rows = items.toSharedListRows(selectedTrackerId = "")
        val row = rows.single() as SharedListRowModel.GroupRow

        assertEquals("s-g-g1", row.key)
        assertEquals(2, row.trackerCount)
        assertTrue(row.canEdit)
        assertEquals("group-owner@example.com", row.ownerEmail)
    }

    @Test
    fun sharedListActionPolicy_canOpenMapOnlyWithCoordinates() {
        val withPoint = Tracker(
            id = "t1",
            name = "Tracker 1",
            color = null,
            is_owner = false,
            visibility = "shared",
            last_point = listOf(0.0, 1.0, 2.0),
        )
        val withoutPoint = withPoint.copy(id = "t2", last_point = null)

        assertTrue(SharedListActionPolicy.canOpenTrackerMap(withPoint))
        assertFalse(SharedListActionPolicy.canOpenTrackerMap(withoutPoint))
    }
}
