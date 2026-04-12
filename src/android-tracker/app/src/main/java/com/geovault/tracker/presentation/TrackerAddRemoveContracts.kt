package com.geovault.tracker.presentation

sealed interface SharedAddRemoveOperation {
    data class IncomingTrackerAdd(val trackerId: String) : SharedAddRemoveOperation
    data class IncomingGroupAccept(val groupId: String) : SharedAddRemoveOperation
    data class PublicTrackerAdd(val trackerId: String) : SharedAddRemoveOperation
    data class PublicTrackerRemove(val trackerId: String) : SharedAddRemoveOperation
    data class DiscoverOnMapTrackerRemove(val trackerId: String) : SharedAddRemoveOperation
    data class DiscoverOnMapGroupRemove(val groupId: String) : SharedAddRemoveOperation
    data class PublicGroupAdd(val groupId: String) : SharedAddRemoveOperation
    data class PublicGroupRemove(val groupId: String) : SharedAddRemoveOperation
}

object TrackerAddRemoveKeyPolicy {
    fun sharedMutationKey(operation: SharedAddRemoveOperation): String {
        return when (operation) {
            is SharedAddRemoveOperation.IncomingGroupAccept -> "incoming-group-${operation.groupId}"
            is SharedAddRemoveOperation.IncomingTrackerAdd -> "incoming-tracker-${operation.trackerId}"
            is SharedAddRemoveOperation.PublicTrackerAdd -> "public-tracker-${operation.trackerId}"
            is SharedAddRemoveOperation.PublicTrackerRemove -> "public-remove-tracker-${operation.trackerId}"
            is SharedAddRemoveOperation.DiscoverOnMapTrackerRemove -> "discover-remove-tracker-${operation.trackerId}"
            is SharedAddRemoveOperation.DiscoverOnMapGroupRemove -> "discover-remove-group-${operation.groupId}"
            is SharedAddRemoveOperation.PublicGroupAdd -> "public-group-${operation.groupId}"
            is SharedAddRemoveOperation.PublicGroupRemove -> "public-remove-group-${operation.groupId}"
        }
    }

    fun sharedMutationPhase(operation: SharedAddRemoveOperation): SharedMutationPhase {
        return when (operation) {
            is SharedAddRemoveOperation.IncomingGroupAccept,
            is SharedAddRemoveOperation.IncomingTrackerAdd,
            is SharedAddRemoveOperation.PublicTrackerAdd,
            is SharedAddRemoveOperation.PublicGroupAdd,
            -> SharedMutationPhase.PENDING_ADD
            is SharedAddRemoveOperation.PublicTrackerRemove,
            is SharedAddRemoveOperation.DiscoverOnMapTrackerRemove,
            is SharedAddRemoveOperation.DiscoverOnMapGroupRemove,
            is SharedAddRemoveOperation.PublicGroupRemove,
            -> SharedMutationPhase.PENDING_REMOVE
        }
    }
}

