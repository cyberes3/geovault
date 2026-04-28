package com.geovault.tracker.presentation

import com.geovault.tracker.Group
import com.geovault.tracker.Tracker

data class SharedTrackerEditActions(
    val canUnsubscribe: Boolean,
    val canLeaveShare: Boolean,
)

object SharedEditActionPolicy {
    fun trackerActions(tracker: Tracker): SharedTrackerEditActions {
        val leaveKind = OwnershipActionPolicy.trackerLeaveKind(tracker)
        return SharedTrackerEditActions(
            canUnsubscribe = leaveKind == TrackerLeaveKind.Unsubscribe,
            canLeaveShare = leaveKind == TrackerLeaveKind.LeaveShare,
        )
    }

    fun canOpenSharedGroupEdit(group: Group): Boolean = !group.isOwner()
}
