package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupMembershipSyncPolicyTest {

    @Test
    fun plan_returnsNoOpForEquivalentSets() {
        val plan = GroupMembershipSyncPolicy.plan(
            currentTrackerIds = listOf("a", " b ", "a"),
            targetTrackerIds = listOf("b", "a")
        )

        assertTrue(plan.isNoOp)
        assertEquals(emptySet<String>(), plan.addIds)
        assertEquals(emptySet<String>(), plan.removeIds)
    }

    @Test
    fun plan_computesAddsAndRemoves() {
        val plan = GroupMembershipSyncPolicy.plan(
            currentTrackerIds = listOf("a", "b", "c"),
            targetTrackerIds = listOf("b", "d")
        )

        assertEquals(setOf("a", "c"), plan.removeIds)
        assertEquals(setOf("d"), plan.addIds)
    }
}
