package com.geovault.tracker.presentation

import com.geovault.tracker.Group
import com.geovault.tracker.Tracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedScreenTransformsTest {

    @Test
    fun isSharedOrPublicNonOwnedTracker_sharedVisibility() {
        val t = Tracker(id = "a", name = "S", color = null, is_owner = false, visibility = "shared")
        assertTrue(isSharedOrPublicNonOwnedTracker(t))
    }

    @Test
    fun isSharedOrPublicNonOwnedTracker_publicVisibility() {
        val t = Tracker(id = "b", name = "P", color = null, is_owner = false, visibility = "public")
        assertTrue(isSharedOrPublicNonOwnedTracker(t))
    }

    @Test
    fun isSharedOrPublicNonOwnedTracker_ownedExcluded() {
        val t = Tracker(id = "c", name = "O", color = null, is_owner = true, visibility = "shared")
        assertFalse(isSharedOrPublicNonOwnedTracker(t))
    }

    @Test
    fun isSharedOrPublicNonOwnedTracker_privateExcluded() {
        val t = Tracker(id = "d", name = "Pr", color = null, is_owner = false, visibility = "private")
        assertFalse(isSharedOrPublicNonOwnedTracker(t))
    }

    @Test
    fun computeVisibleSharedTrackers_dedupesTracksInNonOwnedGroup() {
        val trackers = listOf(
            Tracker(id = "t1", name = "In group", color = null, is_owner = false, visibility = "shared"),
            Tracker(id = "t2", name = "Standalone", color = null, is_owner = false, visibility = "public"),
        )
        val groups = listOf(
            Group(
                id = "g1",
                name = "G",
                is_owner = false,
                visibility = "shared",
                is_accepted = true,
                track_ids = listOf("t1"),
            ),
        )
        val visible = computeVisibleSharedTrackers(trackers, groups)
        assertEquals(listOf("t2"), visible.map { it.id })
    }

    @Test
    fun computeVisibleSharedTrackers_sortedByNameCaseInsensitive() {
        val trackers = listOf(
            Tracker(id = "b", name = "beta", color = null, is_owner = false, visibility = "public"),
            Tracker(id = "a", name = "Alpha", color = null, is_owner = false, visibility = "public"),
        )
        val visible = computeVisibleSharedTrackers(trackers, emptyList())
        assertEquals(listOf("a", "b"), visible.map { it.id })
    }

    @Test
    fun computeVisibleSharedGroups_filtersAcceptedSharedNonOwned() {
        val groups = listOf(
            Group(
                id = "g1",
                name = "Ok",
                is_owner = false,
                visibility = "shared",
                is_accepted = true,
            ),
            Group(
                id = "g2",
                name = "Pending",
                is_owner = false,
                visibility = "shared",
                is_accepted = false,
            ),
            Group(
                id = "g3",
                name = "Public member",
                is_owner = false,
                visibility = "public",
                is_accepted = true,
            ),
            Group(
                id = "g4",
                name = "Owned",
                is_owner = true,
                visibility = "shared",
                is_accepted = true,
            ),
        )
        val visible = computeVisibleSharedGroups(groups)
        assertEquals(listOf("g1"), visible.map { it.id })
    }
}
