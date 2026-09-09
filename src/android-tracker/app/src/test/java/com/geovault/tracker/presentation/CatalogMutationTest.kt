package com.geovault.tracker.presentation

import com.geovault.tracker.AvailableToAddItem
import com.geovault.tracker.Tracker
import com.geovault.tracker.data.CatalogEntityType
import com.geovault.tracker.data.MutationQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogMutationTest {
    @Test
    fun occupancyKey_keepsAddAndRemoveDistinct() {
        val addOp = SharedAddRemoveOperation.PublicTrackerAdd("t-1")
        val removeOp = SharedAddRemoveOperation.PublicTrackerRemove("t-1")

        assertEquals("public-tracker-t-1", CatalogMutation.occupancyKey(addOp))
        assertEquals(SharedMutationPhase.PENDING_ADD, CatalogMutation.phase(addOp))
        assertEquals("public-remove-tracker-t-1", CatalogMutation.occupancyKey(removeOp))
        assertEquals(SharedMutationPhase.PENDING_REMOVE, CatalogMutation.phase(removeOp))
    }

    @Test
    fun begin_incomingAddSetsQueuedAndPendingTracker() {
        val incoming = AvailableToAddItem(id = "t-1", name = "Tracker 1")
        val added = Tracker(id = "t-1", name = "Tracker 1", color = null, is_owner = false, visibility = "shared")
        val tx = CatalogMutation.begin(
            operation = SharedAddRemoveOperation.IncomingTrackerAdd("t-1"),
            addedTracker = added,
            incomingTracker = incoming,
            incomingGroup = null,
            publicTracker = null,
            publicGroup = null,
        )

        assertEquals("incoming-tracker-t-1", tx.occupancyKey)
        assertEquals("t-1", tx.addedTrackerId)
        assertEquals(incoming, tx.incomingTracker)
        val state = SharedUiState(mutations = listOf(tx))
        assertTrue(state.pendingAddActionKeys.contains("incoming-tracker-t-1"))
        assertTrue(state.pendingTrackerAdds.containsKey("t-1"))
        assertTrue(state.queuedIncomingTrackers.containsKey("t-1"))
    }

    @Test
    fun complete_clearsIncomingAddProjection() {
        val queue = MutationQueue()
        val tx = CatalogMutation.begin(
            operation = SharedAddRemoveOperation.IncomingTrackerAdd("t-1"),
            addedTracker = Tracker(id = "t-1", name = "Tracker 1", color = null, is_owner = false, visibility = "shared"),
            incomingTracker = AvailableToAddItem(id = "t-1", name = "Tracker 1"),
            incomingGroup = null,
            publicTracker = null,
            publicGroup = null,
        )
        assertTrue(queue.enqueue(tx))
        val occupancy = CatalogMutation.occupancy(SharedAddRemoveOperation.IncomingTrackerAdd("t-1"))
        queue.complete(occupancy.first, occupancy.second)
        val state = SharedUiState(mutations = queue.state.value)
        assertFalse(state.pendingTrackerAdds.containsKey("t-1"))
        assertFalse(state.queuedIncomingTrackers.containsKey("t-1"))
    }

    @Test
    fun membership_isIdempotentInQueue() {
        val queue = MutationQueue()
        assertTrue(queue.enqueue(CatalogMutation.membership("t-1")))
        assertFalse(queue.enqueue(CatalogMutation.membership("t-1")))
        queue.complete(CatalogEntityType.Membership, "t-1")
        assertTrue(queue.membershipIds().isEmpty())
    }
}
