package com.geovault.tracker.data

import com.geovault.tracker.Group
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerAddToGroupCandidate
import javax.inject.Inject

class DefaultGroupTrackerEligibilityUseCase @Inject constructor() : GroupTrackerEligibilityUseCase {
    override fun addableTrackers(
        trackers: List<Tracker>,
        group: Group
    ): List<TrackerAddToGroupCandidate> {
        val alreadyInGroup = (group.track_ids ?: emptyList()).toSet()
        return trackers.map { tracker ->
            val canAdd = tracker.id !in alreadyInGroup && if (tracker.isOwner()) {
                ((tracker.settings?.get("hidden_in_list") as? Boolean) != true)
            } else {
                ((tracker.settings?.get("allow_group_reshare") as? Boolean) == true) &&
                    tracker.visibility == "public"
            }
            TrackerAddToGroupCandidate(
                tracker = tracker,
                canAdd = canAdd,
                reason = if (canAdd) null else "Not eligible"
            )
        }
    }
}
