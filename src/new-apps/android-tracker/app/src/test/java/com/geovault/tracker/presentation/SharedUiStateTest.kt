package com.geovault.tracker.presentation

import com.geovault.tracker.AvailableToAddGroup
import com.geovault.tracker.AvailableToAddItem
import com.geovault.tracker.AvailableToAddResponse
import com.geovault.tracker.Group
import com.geovault.tracker.Tracker
import org.junit.Assert.assertEquals
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
    fun publicAddedHelpers_useEffectiveSubscribedIdsWithOptimisticDelta() {
        val state = SharedUiState(
            trackers = listOf(
                Tracker(id = "t-base", name = "Base", color = null, is_owner = false, visibility = "shared"),
            ),
            optimisticTrackerAdds = mapOf(
                "t-add" to Tracker(id = "t-add", name = "Add", color = null, is_owner = false, visibility = "shared"),
            ),
            optimisticTrackerRemovals = setOf("t-base"),
        )

        assertEquals(true, state.isPublicTrackerAdded("t-add"))
        assertEquals(false, state.isPublicTrackerAdded("t-base"))
        assertEquals(true, state.isPublicGroupAdded(listOf("t-add")))
        assertEquals(false, state.isPublicGroupAdded(listOf("t-base")))
    }
}
