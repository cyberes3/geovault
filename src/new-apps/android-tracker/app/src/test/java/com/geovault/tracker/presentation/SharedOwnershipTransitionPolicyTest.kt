package com.geovault.tracker.presentation

import com.geovault.tracker.Tracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedOwnershipTransitionPolicyTest {

    @Test
    fun forTrackerLeave_ownerReturnsNull() {
        val tracker = Tracker(id = "t1", name = "Mine", color = null, is_owner = true)

        val command = SharedOwnershipTransitionPolicy.forTrackerLeave(tracker)

        assertNull(command)
    }

    @Test
    fun forTrackerLeave_subscribedReturnsUnsubscribe() {
        val tracker = Tracker(
            id = "t2",
            name = "Public",
            color = null,
            is_owner = false,
            subscribed_at = 123L
        )

        val command = SharedOwnershipTransitionPolicy.forTrackerLeave(tracker)

        assertEquals(
            SharedTrackerTransitionCommand(
                trackerId = "t2",
                action = SharedTrackerTransitionAction.Unsubscribe
            ),
            command
        )
    }

    @Test
    fun forTrackerLeave_notSubscribedReturnsLeaveShare() {
        val tracker = Tracker(
            id = "t3",
            name = "Shared",
            color = null,
            is_owner = false,
            subscribed_at = null
        )

        val command = SharedOwnershipTransitionPolicy.forTrackerLeave(tracker)

        assertEquals(
            SharedTrackerTransitionCommand(
                trackerId = "t3",
                action = SharedTrackerTransitionAction.LeaveShare
            ),
            command
        )
    }

    @Test
    fun trackerSubscribeCommands_areMappedToSubscribe() {
        assertEquals(
            SharedTrackerTransitionCommand("a", SharedTrackerTransitionAction.Subscribe),
            SharedOwnershipTransitionPolicy.forIncomingTrackerSubscribe("a")
        )
        assertEquals(
            SharedTrackerTransitionCommand("b", SharedTrackerTransitionAction.Subscribe),
            SharedOwnershipTransitionPolicy.forPublicTrackerSubscribe("b")
        )
    }

    @Test
    fun incomingReject_mapsToLeaveShare() {
        assertEquals(
            SharedTrackerTransitionCommand("z", SharedTrackerTransitionAction.LeaveShare),
            SharedOwnershipTransitionPolicy.forIncomingTrackerReject("z")
        )
    }

    @Test
    fun groupCommands_mapToExpectedActions() {
        assertEquals(
            SharedGroupTransitionCommand("g1", SharedGroupTransitionAction.AcceptShare),
            SharedOwnershipTransitionPolicy.forGroupAccept("g1")
        )
        assertEquals(
            SharedGroupTransitionCommand("g2", SharedGroupTransitionAction.LeaveGroup),
            SharedOwnershipTransitionPolicy.forGroupLeave("g2")
        )
    }
}
