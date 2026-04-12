package com.geovault.tracker.presentation

internal object TrackersGroupAddMutationPolicy {
    fun tryBegin(addingTrackerIds: Set<String>, trackerId: String): Pair<Boolean, Set<String>> {
        return if (addingTrackerIds.contains(trackerId)) {
            false to addingTrackerIds
        } else {
            true to (addingTrackerIds + trackerId)
        }
    }

    fun settle(addingTrackerIds: Set<String>, trackerId: String): Set<String> {
        return addingTrackerIds - trackerId
    }
}
