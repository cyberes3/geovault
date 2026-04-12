package com.geovault.tracker.presentation

import com.geovault.tracker.Group
import com.geovault.tracker.Tracker

enum class TrackerLeaveKind {
    Unsubscribe,
    LeaveShare,
}

object OwnershipActionPolicy {

    fun trackerLeaveKind(tracker: Tracker): TrackerLeaveKind? {
        if (tracker.isOwner()) return null
        return if (tracker.subscribed_at != null) {
            TrackerLeaveKind.Unsubscribe
        } else {
            TrackerLeaveKind.LeaveShare
        }
    }

    fun canEditTracker(tracker: Tracker): Boolean = tracker.isOwner()

    fun groupPendingAccept(group: Group): Boolean = group.is_accepted == false

    fun groupCanLeave(group: Group): Boolean = !group.isOwner() && !groupPendingAccept(group)

    fun canEditGroup(group: Group): Boolean = group.isOwner()
}
