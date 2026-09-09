package com.geovault.tracker.presentation

import com.geovault.tracker.AvailableToAddGroup
import com.geovault.tracker.AvailableToAddItem
import com.geovault.tracker.Tracker
import com.geovault.tracker.data.CatalogEntityType
import com.geovault.tracker.data.PendingTransaction

object CatalogMutation {
    fun occupancyKey(operation: SharedAddRemoveOperation): String {
        return when (operation) {
            is SharedAddRemoveOperation.IncomingGroupAccept -> "incoming-group-${operation.groupId}"
            is SharedAddRemoveOperation.IncomingTrackerAdd -> "incoming-tracker-${operation.trackerId}"
            is SharedAddRemoveOperation.IncomingTrackerReject -> "incoming-reject-${operation.trackerId}"
            is SharedAddRemoveOperation.PublicTrackerAdd -> "public-tracker-${operation.trackerId}"
            is SharedAddRemoveOperation.PublicTrackerRemove -> "public-remove-tracker-${operation.trackerId}"
            is SharedAddRemoveOperation.DiscoverOnMapTrackerRemove -> "discover-remove-tracker-${operation.trackerId}"
            is SharedAddRemoveOperation.DiscoverOnMapGroupRemove -> "discover-remove-group-${operation.groupId}"
            is SharedAddRemoveOperation.PublicGroupAdd -> "public-group-${operation.groupId}"
            is SharedAddRemoveOperation.PublicGroupRemove -> "public-remove-group-${operation.groupId}"
        }
    }

    fun phase(operation: SharedAddRemoveOperation): SharedMutationPhase {
        return when (operation) {
            is SharedAddRemoveOperation.IncomingGroupAccept,
            is SharedAddRemoveOperation.IncomingTrackerAdd,
            is SharedAddRemoveOperation.PublicTrackerAdd,
            is SharedAddRemoveOperation.PublicGroupAdd,
            -> SharedMutationPhase.PENDING_ADD
            is SharedAddRemoveOperation.IncomingTrackerReject,
            is SharedAddRemoveOperation.PublicTrackerRemove,
            is SharedAddRemoveOperation.DiscoverOnMapTrackerRemove,
            is SharedAddRemoveOperation.DiscoverOnMapGroupRemove,
            is SharedAddRemoveOperation.PublicGroupRemove,
            -> SharedMutationPhase.PENDING_REMOVE
        }
    }

    fun occupancy(operation: SharedAddRemoveOperation): Pair<CatalogEntityType, String> {
        return when (operation) {
            is SharedAddRemoveOperation.IncomingTrackerAdd -> CatalogEntityType.Share to operation.trackerId
            is SharedAddRemoveOperation.IncomingTrackerReject -> CatalogEntityType.Share to operation.trackerId
            is SharedAddRemoveOperation.PublicTrackerAdd -> CatalogEntityType.Tracker to "public-add-${operation.trackerId}"
            is SharedAddRemoveOperation.PublicTrackerRemove -> CatalogEntityType.Tracker to "public-remove-${operation.trackerId}"
            is SharedAddRemoveOperation.DiscoverOnMapTrackerRemove -> CatalogEntityType.Tracker to operation.trackerId
            is SharedAddRemoveOperation.IncomingGroupAccept -> CatalogEntityType.Group to operation.groupId
            is SharedAddRemoveOperation.PublicGroupAdd -> CatalogEntityType.Group to "public-add-${operation.groupId}"
            is SharedAddRemoveOperation.PublicGroupRemove -> CatalogEntityType.Group to "public-remove-${operation.groupId}"
            is SharedAddRemoveOperation.DiscoverOnMapGroupRemove -> CatalogEntityType.Group to operation.groupId
        }
    }

    fun begin(
        operation: SharedAddRemoveOperation,
        addedTracker: Tracker?,
        incomingTracker: AvailableToAddItem?,
        incomingGroup: AvailableToAddGroup?,
        publicTracker: AvailableToAddItem?,
        publicGroup: AvailableToAddGroup?,
    ): PendingTransaction {
        val (entityType, entityId) = occupancy(operation)
        val occupancyKey = occupancyKey(operation)
        val phase = phase(operation).name
        val op = operation::class.java.simpleName
        return when (operation) {
            is SharedAddRemoveOperation.IncomingTrackerAdd -> PendingTransaction(
                entityType = entityType,
                entityId = entityId,
                op = op,
                phase = phase,
                occupancyKey = occupancyKey,
                addedTrackerId = operation.trackerId,
                addedTracker = addedTracker,
                incomingTracker = incomingTracker,
            )
            is SharedAddRemoveOperation.IncomingTrackerReject -> PendingTransaction(
                entityType = entityType,
                entityId = entityId,
                op = op,
                phase = phase,
                occupancyKey = occupancyKey,
                incomingTracker = incomingTracker,
            )
            is SharedAddRemoveOperation.PublicTrackerAdd -> PendingTransaction(
                entityType = entityType,
                entityId = entityId,
                op = op,
                phase = phase,
                occupancyKey = occupancyKey,
                addedTrackerId = operation.trackerId,
                addedTracker = addedTracker,
                publicTracker = publicTracker,
            )
            is SharedAddRemoveOperation.PublicTrackerRemove -> PendingTransaction(
                entityType = entityType,
                entityId = entityId,
                op = op,
                phase = phase,
                occupancyKey = occupancyKey,
                removalTrackerId = operation.trackerId,
            )
            is SharedAddRemoveOperation.DiscoverOnMapTrackerRemove -> PendingTransaction(
                entityType = entityType,
                entityId = entityId,
                op = op,
                phase = phase,
                occupancyKey = occupancyKey,
                removalTrackerId = operation.trackerId,
            )
            is SharedAddRemoveOperation.IncomingGroupAccept -> PendingTransaction(
                entityType = entityType,
                entityId = entityId,
                op = op,
                phase = phase,
                occupancyKey = occupancyKey,
                incomingGroup = incomingGroup,
            )
            is SharedAddRemoveOperation.PublicGroupAdd -> PendingTransaction(
                entityType = entityType,
                entityId = entityId,
                op = op,
                phase = phase,
                occupancyKey = occupancyKey,
                publicGroup = publicGroup,
            )
            is SharedAddRemoveOperation.DiscoverOnMapGroupRemove,
            is SharedAddRemoveOperation.PublicGroupRemove,
            -> PendingTransaction(
                entityType = entityType,
                entityId = entityId,
                op = op,
                phase = phase,
                occupancyKey = occupancyKey,
            )
        }
    }

    fun editOccupancy(key: String): Pair<CatalogEntityType, String> = CatalogEntityType.Share to key

    fun membership(trackerId: String): PendingTransaction {
        return PendingTransaction(
            entityType = CatalogEntityType.Membership,
            entityId = trackerId,
            op = "add_to_group",
            phase = SharedMutationPhase.PENDING_ADD.name,
            occupancyKey = trackerId,
        )
    }
}
