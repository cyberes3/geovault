package com.geovault.tracker.presentation

import com.geovault.tracker.Group
import com.geovault.tracker.Tracker

object SharedListActionPolicy {
    fun canOpenTrackerMap(tracker: Tracker): Boolean =
        tracker.last_point?.size?.let { it >= 2 } == true

    fun canEditTracker(tracker: Tracker): Boolean = !tracker.id.isBlank()

    fun canEditGroup(group: Group): Boolean =
        SharedEditActionPolicy.canOpenSharedGroupEdit(group) || group.isOwner()
}
