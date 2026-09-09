package com.geovault.tracker.presentation

sealed interface SharedAddRemoveOperation {
    data class IncomingTrackerAdd(val trackerId: String) : SharedAddRemoveOperation
    data class IncomingTrackerReject(val trackerId: String) : SharedAddRemoveOperation
    data class IncomingGroupAccept(val groupId: String) : SharedAddRemoveOperation
    data class PublicTrackerAdd(val trackerId: String) : SharedAddRemoveOperation
    data class PublicTrackerRemove(val trackerId: String) : SharedAddRemoveOperation
    data class DiscoverOnMapTrackerRemove(val trackerId: String) : SharedAddRemoveOperation
    data class DiscoverOnMapGroupRemove(val groupId: String) : SharedAddRemoveOperation
    data class PublicGroupAdd(val groupId: String) : SharedAddRemoveOperation
    data class PublicGroupRemove(val groupId: String) : SharedAddRemoveOperation
}

