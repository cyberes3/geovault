package com.geovault.tracker.presentation

import com.geovault.tracker.Tracker

enum class SharedTrackerTransitionAction {
    Subscribe,
    Unsubscribe,
    LeaveShare,
}

enum class SharedGroupTransitionAction {
    AcceptShare,
    LeaveGroup,
}

data class SharedTrackerTransitionCommand(
    val trackerId: String,
    val action: SharedTrackerTransitionAction
)

data class SharedGroupTransitionCommand(
    val groupId: String,
    val action: SharedGroupTransitionAction
)

object SharedOwnershipTransitionPolicy {

    fun forTrackerLeave(tracker: Tracker): SharedTrackerTransitionCommand? {
        val action = when (OwnershipActionPolicy.trackerLeaveKind(tracker)) {
            TrackerLeaveKind.Unsubscribe -> SharedTrackerTransitionAction.Unsubscribe
            TrackerLeaveKind.LeaveShare -> SharedTrackerTransitionAction.LeaveShare
            null -> null
        } ?: return null
        return SharedTrackerTransitionCommand(trackerId = tracker.id, action = action)
    }

    fun forIncomingTrackerSubscribe(trackerId: String): SharedTrackerTransitionCommand {
        return SharedTrackerTransitionCommand(
            trackerId = trackerId,
            action = SharedTrackerTransitionAction.Subscribe
        )
    }

    fun forIncomingTrackerReject(trackerId: String): SharedTrackerTransitionCommand {
        return SharedTrackerTransitionCommand(
            trackerId = trackerId,
            action = SharedTrackerTransitionAction.LeaveShare
        )
    }

    fun forPublicTrackerSubscribe(trackerId: String): SharedTrackerTransitionCommand {
        return forIncomingTrackerSubscribe(trackerId)
    }

    fun forGroupAccept(groupId: String): SharedGroupTransitionCommand {
        return SharedGroupTransitionCommand(
            groupId = groupId,
            action = SharedGroupTransitionAction.AcceptShare
        )
    }

    fun forGroupLeave(groupId: String): SharedGroupTransitionCommand {
        return SharedGroupTransitionCommand(
            groupId = groupId,
            action = SharedGroupTransitionAction.LeaveGroup
        )
    }
}
