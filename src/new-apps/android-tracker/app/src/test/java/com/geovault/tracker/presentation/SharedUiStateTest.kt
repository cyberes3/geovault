package com.geovault.tracker.presentation

import com.geovault.tracker.AvailableToAddGroup
import com.geovault.tracker.AvailableToAddItem
import com.geovault.tracker.AvailableToAddResponse
import com.geovault.tracker.Group
import com.geovault.tracker.Tracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedUiStateTest {

    @Test
    fun filteredSections_respectsPerTabQueries() {
        val state = SharedUiState(
            trackers = listOf(
                Tracker(id = "t1", name = "Alpha tracker", color = null, is_owner = false, visibility = "shared"),
            ),
            groups = listOf(
                Group(id = "g1", name = "Beta group", is_owner = false, visibility = "shared", is_accepted = true),
            ),
            availableToAdd = AvailableToAddResponse(
                shared_with_me = listOf(AvailableToAddItem(id = "i1", name = "Gamma tracker")),
                shared_with_me_groups = listOf(AvailableToAddGroup(id = "ig1", name = "Delta group")),
                public = listOf(AvailableToAddItem(id = "p1", name = "Echo tracker")),
                public_groups = listOf(AvailableToAddGroup(id = "pg1", name = "Foxtrot group")),
            ),
            discoverOnMapQuery = "alpha",
            discoverIncomingQuery = "gamma",
            publicQuery = "foxtrot",
        )

        assertEquals(listOf("t1", "g1"), state.filteredSections.sharedItems.map {
            when (it) {
                is SharedSurfaceItem.GroupItem -> it.group.id
                is SharedSurfaceItem.TrackerItem -> it.tracker.id
            }
        })
        assertEquals(listOf("t1"), state.filteredSections.discoverOnMyMapTrackers.map { it.id })
        assertEquals(emptyList<String>(), state.filteredSections.discoverOnMyMapGroups.map { it.id })
        assertEquals(listOf("i1"), state.filteredSections.incomingTrackers.map { it.id })
        assertEquals(emptyList<String>(), state.filteredSections.incomingGroups.map { it.id })
        assertEquals(emptyList<String>(), state.filteredSections.publicTrackers.map { it.id })
        assertEquals(listOf("pg1"), state.filteredSections.publicGroups.map { it.id })
    }

    @Test
    fun pendingActionKeys_areSplitByMutationPhase() {
        val state = SharedUiState(
            pendingOps = mapOf(
                "k-add" to SharedMutationPhase.PENDING_ADD,
                "k-remove" to SharedMutationPhase.PENDING_REMOVE,
            )
        )

        assertEquals(setOf("k-add"), state.pendingAddActionKeys)
        assertEquals(setOf("k-remove"), state.pendingRemoveActionKeys)
    }

    @Test
    fun addedHelpers_useSessionRetainedSets() {
        val state = SharedUiState(
            trackers = listOf(
                Tracker(id = "t-base", name = "Base", color = null, is_owner = false, visibility = "shared"),
            ),
            groups = listOf(
                Group(id = "g-added", name = "Added Group", is_owner = false, visibility = "shared", is_accepted = true),
            ),
            optimisticTrackerAdds = mapOf(
                "t-add" to Tracker(id = "t-add", name = "Add", color = null, is_owner = false, visibility = "shared"),
            ),
            optimisticTrackerRemovals = setOf("t-base"),
            retainedIncomingTrackers = mapOf(
                "t-incoming-added" to AvailableToAddItem(id = "t-incoming-added", name = "Incoming Added")
            ),
            retainedIncomingGroups = mapOf(
                "g-incoming-added" to AvailableToAddGroup(id = "g-incoming-added", name = "Incoming Group Added")
            ),
            retainedPublicTrackers = mapOf(
                "t-public-added" to AvailableToAddItem(id = "t-public-added", name = "Public Added")
            ),
            retainedPublicGroups = mapOf(
                "g-public-added" to AvailableToAddGroup(id = "g-public-added", name = "Public Group Added")
            ),
        )

        assertEquals(true, state.isPublicTrackerAdded("t-public-added"))
        assertEquals(false, state.isPublicTrackerAdded("t-add"))
        assertEquals(true, state.isPublicGroupAdded("g-public-added"))
        assertEquals(false, state.isPublicGroupAdded("g-added"))
        assertEquals(true, state.isIncomingTrackerAdded("t-incoming-added"))
        assertEquals(false, state.isIncomingTrackerAdded("t-add"))
        assertEquals(true, state.isIncomingGroupAdded("g-incoming-added"))
        assertEquals(false, state.isIncomingGroupAdded("g-missing"))
    }

    @Test
    fun filteredSections_includeRetainedRowsWhenSourceListChanges() {
        val state = SharedUiState(
            trackers = listOf(
                Tracker(id = "t-public-added", name = "Public Added", color = null, is_owner = false, visibility = "shared")
            ),
            groups = listOf(
                Group(id = "g-public-added", name = "Public Added Group", is_owner = false, visibility = "shared", is_accepted = true),
                Group(id = "g-incoming-added", name = "Incoming Added Group", is_owner = false, visibility = "shared", is_accepted = true),
            ),
            availableToAdd = AvailableToAddResponse(),
            retainedIncomingTrackers = mapOf(
                "t-incoming-added" to AvailableToAddItem(id = "t-incoming-added", name = "Incoming Added")
            ),
            retainedIncomingGroups = mapOf(
                "g-incoming-added" to AvailableToAddGroup(id = "g-incoming-added", name = "Incoming Added Group")
            ),
            retainedPublicTrackers = mapOf(
                "t-public-added" to AvailableToAddItem(id = "t-public-added", name = "Public Added")
            ),
            retainedPublicGroups = mapOf(
                "g-public-added" to AvailableToAddGroup(id = "g-public-added", name = "Public Added Group")
            ),
        )

        assertEquals(listOf("t-incoming-added"), state.filteredSections.incomingTrackers.map { it.id })
        assertEquals(listOf("g-incoming-added"), state.filteredSections.incomingGroups.map { it.id })
        assertEquals(listOf("t-public-added"), state.filteredSections.publicTrackers.map { it.id })
        assertEquals(listOf("g-public-added"), state.filteredSections.publicGroups.map { it.id })
    }

    @Test
    fun sharedListRows_usesSelectedTrackerFromState() {
        val state = SharedUiState(
            trackers = listOf(
                Tracker(
                    id = "t1",
                    name = "Alpha tracker",
                    color = null,
                    is_owner = false,
                    visibility = "shared",
                    last_point = listOf(10.0, 20.0, 1234.0),
                )
            ),
            groups = emptyList(),
            selectedTrackerId = "t1",
        )

        val trackerRow = state.sharedListRows.first() as SharedListRowModel.TrackerRow

        assertTrue(trackerRow.isSelected)
        assertTrue(trackerRow.canOpenMap)
    }
}
