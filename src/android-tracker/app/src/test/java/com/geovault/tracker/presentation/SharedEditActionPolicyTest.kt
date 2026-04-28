package com.geovault.tracker.presentation

import com.geovault.tracker.Group
import com.geovault.tracker.Tracker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedEditActionPolicyTest {

    @Test
    fun trackerActions_unsubscribeOnly_whenSubscribedNonOwner() {
        val tracker = Tracker(
            id = "t1",
            name = "Tracker 1",
            color = null,
            is_owner = false,
            visibility = "public",
            subscribed_at = 123L,
        )

        val actions = SharedEditActionPolicy.trackerActions(tracker)
        assertTrue(actions.canUnsubscribe)
        assertFalse(actions.canLeaveShare)
    }

    @Test
    fun trackerActions_unsubscribeOnly_whenSubscribedSharedNonOwner() {
        val tracker = Tracker(
            id = "t1-shared",
            name = "Tracker 1 Shared",
            color = null,
            is_owner = false,
            visibility = "shared",
            subscribed_at = 123L,
        )

        val actions = SharedEditActionPolicy.trackerActions(tracker)
        assertTrue(actions.canUnsubscribe)
        assertFalse(actions.canLeaveShare)
    }

    @Test
    fun trackerActions_leaveShare_whenNotSubscribedShared() {
        val tracker = Tracker(
            id = "t2",
            name = "Tracker 2",
            color = null,
            is_owner = false,
            visibility = "shared",
            subscribed_at = null,
        )

        val actions = SharedEditActionPolicy.trackerActions(tracker)
        assertFalse(actions.canUnsubscribe)
        assertTrue(actions.canLeaveShare)
    }

    @Test
    fun canOpenSharedGroupEdit_falseForOwner_trueForMember() {
        val ownerGroup = Group(id = "g1", name = "Owner Group", is_owner = true)
        val memberGroup = Group(id = "g2", name = "Member Group", is_owner = false)

        assertFalse(SharedEditActionPolicy.canOpenSharedGroupEdit(ownerGroup))
        assertTrue(SharedEditActionPolicy.canOpenSharedGroupEdit(memberGroup))
    }
}
