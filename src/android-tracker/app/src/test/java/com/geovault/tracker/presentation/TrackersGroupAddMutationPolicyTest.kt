package com.geovault.tracker.presentation

import com.geovault.tracker.data.CatalogEntityType
import com.geovault.tracker.data.MutationQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackersGroupAddMutationPolicyTest {

    @Test
    fun enqueue_suppressesDuplicateTrackerAdd() {
        val queue = MutationQueue()
        assertTrue(queue.enqueue(CatalogMutation.membership("t1")))
        assertFalse(queue.enqueue(CatalogMutation.membership("t1")))
        assertEquals(setOf("t1"), queue.membershipIds())
    }

    @Test
    fun enqueue_allowsParallelAddsForDistinctTrackers() {
        val queue = MutationQueue()
        assertTrue(queue.enqueue(CatalogMutation.membership("t1")))
        assertTrue(queue.enqueue(CatalogMutation.membership("t2")))
        assertEquals(setOf("t1", "t2"), queue.membershipIds())
    }

    @Test
    fun complete_clearsOnlySettledTracker() {
        val queue = MutationQueue()
        assertTrue(queue.enqueue(CatalogMutation.membership("t1")))
        assertTrue(queue.enqueue(CatalogMutation.membership("t2")))
        queue.complete(CatalogEntityType.Membership, "t1")
        assertEquals(setOf("t2"), queue.membershipIds())
        queue.complete(CatalogEntityType.Membership, "t2")
        assertEquals(emptySet<String>(), queue.membershipIds())
    }
}
