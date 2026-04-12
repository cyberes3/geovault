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

    @Test
    fun computeSharedSurfaceItems_mergesAndSortsNaturally() {
        val trackers = listOf(
            Tracker(id = "t10", name = "tracker 10", color = null, is_owner = false, visibility = "shared"),
            Tracker(id = "t2", name = "tracker 2", color = null, is_owner = false, visibility = "shared"),
        )
        val groups = listOf(
            Group(
                id = "g1",
                name = "group 1",
                is_owner = false,
                visibility = "shared",
                is_accepted = true,
            ),
        )

        val items = computeSharedSurfaceItems(trackers = trackers, groups = groups)

        assertEquals(3, items.size)
        assertEquals("g1", (items[0] as SharedSurfaceItem.GroupItem).group.id)
        assertEquals("t2", (items[1] as SharedSurfaceItem.TrackerItem).tracker.id)
        assertEquals("t10", (items[2] as SharedSurfaceItem.TrackerItem).tracker.id)
    }

    @Test
    fun computeSharedSurfaceItems_excludesStandaloneTrackerIfRepresentedInSharedGroup() {
        val trackers = listOf(
            Tracker(id = "t1", name = "member tracker", color = null, is_owner = false, visibility = "shared"),
            Tracker(id = "t2", name = "standalone", color = null, is_owner = false, visibility = "shared"),
        )
        val groups = listOf(
            Group(
                id = "g1",
                name = "Shared Group",
                is_owner = false,
                visibility = "shared",
                is_accepted = true,
                track_ids = listOf("t1"),
            ),
        )

        val items = computeSharedSurfaceItems(trackers = trackers, groups = groups)

        assertEquals(listOf("g1", "t2"), items.map {
            when (it) {
                is SharedSurfaceItem.GroupItem -> it.group.id
                is SharedSurfaceItem.TrackerItem -> it.tracker.id
            }
        })
    }

    @Test
    fun deriveSharedFilteredSections_filtersEachBucketByTabQuery() {
        val sharedItems = listOf(
            SharedSurfaceItem.TrackerItem(
                Tracker(id = "t1", name = "Alpha tracker", color = null, is_owner = false, visibility = "shared")
            ),
            SharedSurfaceItem.GroupItem(
                Group(id = "g1", name = "Bravo group", is_owner = false, visibility = "shared", is_accepted = true)
            ),
        )
        val incomingTrackers = listOf(
            com.geovault.tracker.AvailableToAddItem(id = "i1", name = "Charlie tracker")
        )
        val incomingGroups = listOf(
            com.geovault.tracker.AvailableToAddGroup(id = "ig1", name = "Delta group")
        )
        val onMyMapTrackers = listOf(
            com.geovault.tracker.AvailableToAddItem(id = "m1", name = "Map tracker")
        )
        val onMyMapGroups = listOf(
            com.geovault.tracker.AvailableToAddGroup(id = "mg1", name = "Map group")
        )
        val publicTrackers = listOf(
            com.geovault.tracker.AvailableToAddItem(id = "p1", name = "Echo tracker")
        )
        val publicGroups = listOf(
            com.geovault.tracker.AvailableToAddGroup(id = "pg1", name = "Foxtrot group")
        )

        val filtered = deriveSharedFilteredSections(
            sharedItems = sharedItems,
            discoverOnMyMapTrackers = onMyMapTrackers,
            discoverOnMyMapGroups = onMyMapGroups,
            incomingTrackers = incomingTrackers,
            incomingGroups = incomingGroups,
            publicTrackers = publicTrackers,
            publicGroups = publicGroups,
            discoverOnMapQuery = "map",
            discoverIncomingQuery = "delta",
            publicQuery = "echo",
        )

        assertEquals(listOf("t1", "g1"), filtered.sharedItems.map {
            when (it) {
                is SharedSurfaceItem.TrackerItem -> it.tracker.id
                is SharedSurfaceItem.GroupItem -> it.group.id
            }
        })
        assertEquals(listOf("m1"), filtered.discoverOnMyMapTrackers.map { it.id })
        assertEquals(listOf("mg1"), filtered.discoverOnMyMapGroups.map { it.id })
        assertEquals(emptyList<String>(), filtered.incomingTrackers.map { it.id })
        assertEquals(listOf("ig1"), filtered.incomingGroups.map { it.id })
        assertEquals(listOf("p1"), filtered.publicTrackers.map { it.id })
        assertEquals(emptyList<String>(), filtered.publicGroups.map { it.id })
    }

    @Test
    fun deriveSharedFilteredSections_appliesOptimisticTrackerAddAndRemove() {
        val sharedItems = listOf(
            SharedSurfaceItem.TrackerItem(
                Tracker(id = "remove-me", name = "Remove Me", color = null, is_owner = false, visibility = "shared")
            ),
            SharedSurfaceItem.GroupItem(
                Group(id = "g1", name = "Base Group", is_owner = false, visibility = "shared", is_accepted = true)
            ),
        )

        val filtered = deriveSharedFilteredSections(
            sharedItems = sharedItems,
            discoverOnMyMapTrackers = emptyList(),
            discoverOnMyMapGroups = emptyList(),
            incomingTrackers = emptyList(),
            incomingGroups = emptyList(),
            publicTrackers = emptyList(),
            publicGroups = emptyList(),
            discoverOnMapQuery = "",
            discoverIncomingQuery = "",
            publicQuery = "",
            optimisticTrackerAdds = mapOf(
                "add-me" to Tracker(
                    id = "add-me",
                    name = "Add Me",
                    color = null,
                    is_owner = false,
                    visibility = "shared",
                )
            ),
            optimisticTrackerRemovals = setOf("remove-me"),
        )

        assertEquals(
            listOf("add-me", "g1"),
            filtered.sharedItems.map {
                when (it) {
                    is SharedSurfaceItem.GroupItem -> it.group.id
                    is SharedSurfaceItem.TrackerItem -> it.tracker.id
                }
            }
        )
    }

    @Test
    fun deriveSharedFilteredSections_keepsOnMapVisibleDuringPendingRemove() {
        val onMyMap = listOf(
            com.geovault.tracker.AvailableToAddItem(id = "t1", name = "Tracker 1")
        )
        val incoming = emptyList<com.geovault.tracker.AvailableToAddItem>()

        val filtered = deriveSharedFilteredSections(
            sharedItems = emptyList(),
            discoverOnMyMapTrackers = onMyMap,
            discoverOnMyMapGroups = emptyList(),
            incomingTrackers = incoming,
            incomingGroups = emptyList(),
            publicTrackers = emptyList(),
            publicGroups = emptyList(),
            discoverOnMapQuery = "",
            discoverIncomingQuery = "",
            publicQuery = "",
            optimisticDiscoverOnMapRemovals = setOf("t1"),
        )

        assertEquals(listOf("t1"), filtered.discoverOnMyMapTrackers.map { it.id })
        assertEquals(emptyList<String>(), filtered.incomingTrackers.map { it.id })
    }

    @Test
    fun deriveSharedFilteredSections_mergesRetainedIncomingAndPublicRows() {
        val filtered = deriveSharedFilteredSections(
            sharedItems = emptyList(),
            discoverOnMyMapTrackers = emptyList(),
            discoverOnMyMapGroups = emptyList(),
            incomingTrackers = listOf(
                com.geovault.tracker.AvailableToAddItem(id = "in-a", name = "Incoming A")
            ),
            incomingGroups = emptyList(),
            publicTrackers = emptyList(),
            publicGroups = listOf(
                com.geovault.tracker.AvailableToAddGroup(id = "pg-a", name = "Public Group A")
            ),
            discoverOnMapQuery = "",
            discoverIncomingQuery = "",
            publicQuery = "",
            retainedIncomingTrackers = listOf(
                com.geovault.tracker.AvailableToAddItem(id = "in-b", name = "Incoming B")
            ),
            retainedIncomingGroups = listOf(
                com.geovault.tracker.AvailableToAddGroup(id = "ig-b", name = "Incoming Group B")
            ),
            retainedPublicTrackers = listOf(
                com.geovault.tracker.AvailableToAddItem(id = "pt-b", name = "Public Tracker B")
            ),
            retainedPublicGroups = listOf(
                com.geovault.tracker.AvailableToAddGroup(id = "pg-b", name = "Public Group B")
            ),
        )

        assertEquals(listOf("in-a", "in-b"), filtered.incomingTrackers.map { it.id })
        assertEquals(listOf("ig-b"), filtered.incomingGroups.map { it.id })
        assertEquals(listOf("pt-b"), filtered.publicTrackers.map { it.id })
        assertEquals(listOf("pg-a", "pg-b"), filtered.publicGroups.map { it.id })
    }

    @Test
    fun deriveSharedFilteredSections_retainedRowsDoNotDuplicateExistingRows() {
        val filtered = deriveSharedFilteredSections(
            sharedItems = emptyList(),
            discoverOnMyMapTrackers = emptyList(),
            discoverOnMyMapGroups = emptyList(),
            incomingTrackers = listOf(
                com.geovault.tracker.AvailableToAddItem(id = "in-a", name = "Incoming A")
            ),
            incomingGroups = listOf(
                com.geovault.tracker.AvailableToAddGroup(id = "ig-a", name = "Incoming Group A")
            ),
            publicTrackers = listOf(
                com.geovault.tracker.AvailableToAddItem(id = "pt-a", name = "Public Tracker A")
            ),
            publicGroups = listOf(
                com.geovault.tracker.AvailableToAddGroup(id = "pg-a", name = "Public Group A")
            ),
            discoverOnMapQuery = "",
            discoverIncomingQuery = "",
            publicQuery = "",
            retainedIncomingTrackers = listOf(
                com.geovault.tracker.AvailableToAddItem(id = "in-a", name = "Incoming A (retained)")
            ),
            retainedIncomingGroups = listOf(
                com.geovault.tracker.AvailableToAddGroup(id = "ig-a", name = "Incoming Group A (retained)")
            ),
            retainedPublicTrackers = listOf(
                com.geovault.tracker.AvailableToAddItem(id = "pt-a", name = "Public Tracker A (retained)")
            ),
            retainedPublicGroups = listOf(
                com.geovault.tracker.AvailableToAddGroup(id = "pg-a", name = "Public Group A (retained)")
            ),
        )

        assertEquals(listOf("in-a"), filtered.incomingTrackers.map { it.id })
        assertEquals(listOf("ig-a"), filtered.incomingGroups.map { it.id })
        assertEquals(listOf("pt-a"), filtered.publicTrackers.map { it.id })
        assertEquals(listOf("pg-a"), filtered.publicGroups.map { it.id })
    }
}
