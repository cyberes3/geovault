package com.geovault.tracker.presentation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupMembershipMutationCoordinatorTest {

    @Test
    fun run_appliesRemovalsBeforeAdditions_andCollectsOutcomes() = runBlocking {
        val callOrder = mutableListOf<String>()
        val outcome = GroupMembershipMutationCoordinator.run(
            plan = GroupMembershipSyncPlan(
                removeIds = setOf("r1", "r2"),
                addIds = setOf("a1", "a2")
            ),
            removeTrackerFromGroup = { id ->
                callOrder += "remove:$id"
                id != "r2"
            },
            addTrackerToGroup = { id ->
                callOrder += "add:$id"
                id != "a2"
            }
        )

        assertEquals(listOf("remove:r1", "remove:r2", "add:a1", "add:a2"), callOrder)
        assertEquals(listOf("r1"), outcome.successfulRemovals)
        assertEquals(listOf("r2"), outcome.failedRemovals)
        assertEquals(listOf("a1"), outcome.successfulAdditions)
        assertEquals(listOf("a2"), outcome.failedAdditions)
        assertEquals(4, outcome.attemptedCount)
        assertEquals(2, outcome.succeededCount)
        assertEquals(2, outcome.failedCount)
    }

    @Test
    fun run_reportsAllSuccessForNoFailures() = runBlocking {
        val outcome = GroupMembershipMutationCoordinator.run(
            plan = GroupMembershipSyncPlan(
                removeIds = setOf("r1"),
                addIds = setOf("a1")
            ),
            removeTrackerFromGroup = { true },
            addTrackerToGroup = { true }
        )

        assertTrue(outcome.hasAnySuccess)
        assertEquals(0, outcome.failedCount)
    }
}
