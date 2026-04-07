package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackersGroupAddMutationPolicyTest {

    @Test
    fun tryBegin_suppressesDuplicateTrackerAdd() {
        val (firstStarted, firstState) = TrackersGroupAddMutationPolicy.tryBegin(
            addingTrackerIds = emptySet(),
            trackerId = "t1",
        )
        val (secondStarted, secondState) = TrackersGroupAddMutationPolicy.tryBegin(
            addingTrackerIds = firstState,
            trackerId = "t1",
        )

        assertTrue(firstStarted)
        assertFalse(secondStarted)
        assertEquals(setOf("t1"), secondState)
    }

    @Test
    fun tryBegin_allowsParallelAddsForDistinctTrackers() {
        val (firstStarted, firstState) = TrackersGroupAddMutationPolicy.tryBegin(
            addingTrackerIds = emptySet(),
            trackerId = "t1",
        )
        val (secondStarted, secondState) = TrackersGroupAddMutationPolicy.tryBegin(
            addingTrackerIds = firstState,
            trackerId = "t2",
        )

        assertTrue(firstStarted)
        assertTrue(secondStarted)
        assertEquals(setOf("t1", "t2"), secondState)
    }

    @Test
    fun settle_clearsOnlySettledTrackerForSuccessAndFailurePaths() {
        val (_, afterStartA) = TrackersGroupAddMutationPolicy.tryBegin(
            addingTrackerIds = emptySet(),
            trackerId = "t1",
        )
        val (_, afterStartB) = TrackersGroupAddMutationPolicy.tryBegin(
            addingTrackerIds = afterStartA,
            trackerId = "t2",
        )

        val afterSuccessCleanup = TrackersGroupAddMutationPolicy.settle(
            addingTrackerIds = afterStartB,
            trackerId = "t1",
        )
        val afterFailureCleanup = TrackersGroupAddMutationPolicy.settle(
            addingTrackerIds = afterSuccessCleanup,
            trackerId = "t2",
        )

        assertEquals(setOf("t2"), afterSuccessCleanup)
        assertEquals(emptySet<String>(), afterFailureCleanup)
    }
}
