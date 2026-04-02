package com.geovault.tracker.presentation

import com.geovault.tracker.Group
import com.geovault.tracker.Tracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnershipActionPolicyTest {

    @Test
    fun trackerLeaveKind_nullForOwner() {
        val t = Tracker(id = "a", name = "Mine", color = null, is_owner = true)
        assertNull(OwnershipActionPolicy.trackerLeaveKind(t))
        assertTrue(OwnershipActionPolicy.canEditTracker(t))
    }

    @Test
    fun trackerLeaveKind_unsubscribeWhenSubscribed() {
        val t = Tracker(id = "b", name = "Pub", color = null, is_owner = false, subscribed_at = 1L)
        assertEquals(TrackerLeaveKind.Unsubscribe, OwnershipActionPolicy.trackerLeaveKind(t))
        assertFalse(OwnershipActionPolicy.canEditTracker(t))
    }

    @Test
    fun trackerLeaveKind_leaveShareWhenNotSubscribed() {
        val t = Tracker(id = "c", name = "Shared", color = null, is_owner = false, subscribed_at = null)
        assertEquals(TrackerLeaveKind.LeaveShare, OwnershipActionPolicy.trackerLeaveKind(t))
    }

    @Test
    fun groupPendingAccept_onlyWhenExplicitlyFalse() {
        val pending = Group(id = "g1", name = "Invited", is_accepted = false)
        assertTrue(OwnershipActionPolicy.groupPendingAccept(pending))
        val accepted = Group(id = "g2", name = "Ok", is_accepted = true)
        assertFalse(OwnershipActionPolicy.groupPendingAccept(accepted))
        val legacy = Group(id = "g3", name = "Legacy", is_accepted = null)
        assertFalse(OwnershipActionPolicy.groupPendingAccept(legacy))
    }

    @Test
    fun groupCanLeave_nonOwnerAcceptedMember() {
        val member = Group(id = "g", name = "Team", is_owner = false, is_accepted = true)
        assertTrue(OwnershipActionPolicy.groupCanLeave(member))
    }

    @Test
    fun groupCanLeave_notWhenOwner() {
        val owner = Group(id = "g", name = "Mine", is_owner = true)
        assertFalse(OwnershipActionPolicy.groupCanLeave(owner))
    }

    @Test
    fun groupCanLeave_notWhenPending() {
        val pending = Group(id = "g", name = "Wait", is_owner = false, is_accepted = false)
        assertFalse(OwnershipActionPolicy.groupCanLeave(pending))
    }

    @Test
    fun canEditGroup_matchesOwnerFlag() {
        val owner = Group(id = "g", name = "Mine", is_owner = true)
        assertTrue(OwnershipActionPolicy.canEditGroup(owner))
        val other = Group(id = "g2", name = "Yours", is_owner = false)
        assertFalse(OwnershipActionPolicy.canEditGroup(other))
    }
}
