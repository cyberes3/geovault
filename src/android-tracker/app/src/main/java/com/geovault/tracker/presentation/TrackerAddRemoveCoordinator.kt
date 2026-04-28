package com.geovault.tracker.presentation

import com.geovault.tracker.AvailableToAddGroup
import com.geovault.tracker.AvailableToAddItem
import com.geovault.tracker.AppError
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.data.GroupManagementRepository
import com.geovault.tracker.data.TrackerManagementRepository

data class SharedMutationStartResult(
    val started: Boolean,
    val key: String,
    val state: SharedUiState,
)

class TrackerAddRemoveCoordinator(
    private val trackerRepository: TrackerManagementRepository,
    private val groupRepository: GroupManagementRepository,
) {
    fun beginSharedMutation(
        state: SharedUiState,
        operation: SharedAddRemoveOperation,
        optimisticTrackerResolver: (String) -> Tracker?,
        incomingTrackerResolver: (String) -> AvailableToAddItem?,
        incomingGroupResolver: (String) -> AvailableToAddGroup?,
        publicTrackerResolver: (String) -> AvailableToAddItem?,
        publicGroupResolver: (String) -> AvailableToAddGroup?,
    ): SharedMutationStartResult {
        val key = TrackerAddRemoveKeyPolicy.sharedMutationKey(operation)
        if (state.pendingOps.containsKey(key)) {
            return SharedMutationStartResult(started = false, key = key, state = state)
        }
        val startedState = applyStart(state, operation, optimisticTrackerResolver, incomingTrackerResolver, incomingGroupResolver, publicTrackerResolver, publicGroupResolver)
        return SharedMutationStartResult(
            started = true,
            key = key,
            state = startedState.copy(
                pendingOps = startedState.pendingOps + (key to TrackerAddRemoveKeyPolicy.sharedMutationPhase(operation))
            ),
        )
    }

    fun clearPendingMutation(state: SharedUiState, key: String): SharedUiState {
        return state.copy(pendingOps = state.pendingOps - key)
    }

    fun applySuccess(state: SharedUiState, operation: SharedAddRemoveOperation): SharedUiState {
        return when (operation) {
            is SharedAddRemoveOperation.IncomingTrackerAdd -> state.copy(
                optimisticTrackerAdds = state.optimisticTrackerAdds - operation.trackerId,
            )
            is SharedAddRemoveOperation.PublicTrackerAdd -> state.copy(
                optimisticTrackerAdds = state.optimisticTrackerAdds - operation.trackerId,
            )
            is SharedAddRemoveOperation.PublicTrackerRemove -> state.copy(
                optimisticTrackerRemovals = state.optimisticTrackerRemovals - operation.trackerId,
                retainedPublicTrackers = state.retainedPublicTrackers - operation.trackerId,
            )
            is SharedAddRemoveOperation.DiscoverOnMapTrackerRemove -> state.copy(
                optimisticTrackerRemovals = state.optimisticTrackerRemovals - operation.trackerId,
                optimisticDiscoverOnMapRemovals = state.optimisticDiscoverOnMapRemovals - operation.trackerId,
                retainedIncomingTrackers = state.retainedIncomingTrackers - operation.trackerId,
            )
            is SharedAddRemoveOperation.DiscoverOnMapGroupRemove -> state.copy(
                retainedIncomingGroups = state.retainedIncomingGroups - operation.groupId,
            )
            is SharedAddRemoveOperation.PublicGroupRemove -> state.copy(
                retainedPublicGroups = state.retainedPublicGroups - operation.groupId,
            )
            is SharedAddRemoveOperation.IncomingGroupAccept,
            is SharedAddRemoveOperation.PublicGroupAdd,
            -> state
        }
    }

    fun applyFailure(state: SharedUiState, operation: SharedAddRemoveOperation): SharedUiState {
        return when (operation) {
            is SharedAddRemoveOperation.IncomingTrackerAdd -> state.copy(
                optimisticTrackerAdds = state.optimisticTrackerAdds - operation.trackerId,
                retainedIncomingTrackers = state.retainedIncomingTrackers - operation.trackerId,
            )
            is SharedAddRemoveOperation.PublicTrackerAdd -> state.copy(
                optimisticTrackerAdds = state.optimisticTrackerAdds - operation.trackerId,
                retainedPublicTrackers = state.retainedPublicTrackers - operation.trackerId,
            )
            is SharedAddRemoveOperation.PublicTrackerRemove -> state.copy(
                optimisticTrackerRemovals = state.optimisticTrackerRemovals - operation.trackerId,
            )
            is SharedAddRemoveOperation.DiscoverOnMapTrackerRemove -> state.copy(
                optimisticTrackerRemovals = state.optimisticTrackerRemovals - operation.trackerId,
                optimisticDiscoverOnMapRemovals = state.optimisticDiscoverOnMapRemovals - operation.trackerId,
            )
            is SharedAddRemoveOperation.IncomingGroupAccept -> state.copy(
                retainedIncomingGroups = state.retainedIncomingGroups - operation.groupId,
            )
            is SharedAddRemoveOperation.PublicGroupAdd -> state.copy(
                retainedPublicGroups = state.retainedPublicGroups - operation.groupId,
            )
            is SharedAddRemoveOperation.DiscoverOnMapGroupRemove,
            is SharedAddRemoveOperation.PublicGroupRemove,
            -> state
        }
    }

    suspend fun executeSharedMutation(
        operation: SharedAddRemoveOperation,
        trackerResolver: (String) -> Tracker?,
    ): RepositoryResult<Unit> {
        return when (operation) {
            is SharedAddRemoveOperation.IncomingTrackerAdd -> trackerRepository
                .subscribeTracker(operation.trackerId)
                .mapToUnit()
            is SharedAddRemoveOperation.PublicTrackerAdd -> trackerRepository
                .subscribeTracker(operation.trackerId)
                .mapToUnit()
            is SharedAddRemoveOperation.PublicTrackerRemove -> trackerRepository
                .unsubscribeTracker(operation.trackerId)
            is SharedAddRemoveOperation.DiscoverOnMapTrackerRemove -> {
                val tracker = trackerResolver(operation.trackerId)
                    ?: return RepositoryResult.Failure(AppError.Unknown)
                val command = SharedOwnershipTransitionPolicy.forTrackerLeave(tracker)
                    ?: return RepositoryResult.Failure(AppError.Unknown)
                executeTrackerCommand(command)
            }
            is SharedAddRemoveOperation.IncomingGroupAccept -> groupRepository
                .acceptGroupShare(operation.groupId)
                .mapToUnit()
            is SharedAddRemoveOperation.DiscoverOnMapGroupRemove -> groupRepository
                .leaveGroup(operation.groupId)
            is SharedAddRemoveOperation.PublicGroupAdd -> groupRepository
                .acceptGroupShare(operation.groupId)
                .mapToUnit()
            is SharedAddRemoveOperation.PublicGroupRemove -> groupRepository
                .leaveGroup(operation.groupId)
        }
    }

    fun tryBeginGroupPickerAdd(addingTrackerIds: Set<String>, trackerId: String): Pair<Boolean, Set<String>> {
        return if (addingTrackerIds.contains(trackerId)) {
            false to addingTrackerIds
        } else {
            true to (addingTrackerIds + trackerId)
        }
    }

    fun settleGroupPickerAdd(addingTrackerIds: Set<String>, trackerId: String): Set<String> {
        return addingTrackerIds - trackerId
    }

    suspend fun addTrackerToGroup(groupId: String, trackerId: String): RepositoryResult<Unit> {
        return groupRepository.addGroupTrack(groupId, trackerId).mapToUnit()
    }

    private suspend fun executeTrackerCommand(command: SharedTrackerTransitionCommand): RepositoryResult<Unit> {
        return when (command.action) {
            SharedTrackerTransitionAction.Subscribe -> trackerRepository
                .subscribeTracker(command.trackerId)
                .mapToUnit()
            SharedTrackerTransitionAction.Unsubscribe -> trackerRepository.unsubscribeTracker(command.trackerId)
            SharedTrackerTransitionAction.LeaveShare -> trackerRepository.leaveShareWithMe(command.trackerId)
        }
    }

    private fun applyStart(
        state: SharedUiState,
        operation: SharedAddRemoveOperation,
        optimisticTrackerResolver: (String) -> Tracker?,
        incomingTrackerResolver: (String) -> AvailableToAddItem?,
        incomingGroupResolver: (String) -> AvailableToAddGroup?,
        publicTrackerResolver: (String) -> AvailableToAddItem?,
        publicGroupResolver: (String) -> AvailableToAddGroup?,
    ): SharedUiState {
        return when (operation) {
            is SharedAddRemoveOperation.IncomingTrackerAdd -> {
                val retained = incomingTrackerResolver(operation.trackerId) ?: state.retainedIncomingTrackers[operation.trackerId]
                state.copy(
                    optimisticTrackerAdds = state.optimisticTrackerAdds + listOfNotNull(
                        optimisticTrackerResolver(operation.trackerId)?.let { operation.trackerId to it }
                    ),
                    optimisticTrackerRemovals = state.optimisticTrackerRemovals - operation.trackerId,
                    optimisticDiscoverOnMapRemovals = state.optimisticDiscoverOnMapRemovals - operation.trackerId,
                    retainedIncomingTrackers = if (retained == null) state.retainedIncomingTrackers else state.retainedIncomingTrackers + (operation.trackerId to retained),
                )
            }
            is SharedAddRemoveOperation.PublicTrackerAdd -> {
                val retained = publicTrackerResolver(operation.trackerId) ?: state.retainedPublicTrackers[operation.trackerId]
                state.copy(
                    optimisticTrackerAdds = state.optimisticTrackerAdds + listOfNotNull(
                        optimisticTrackerResolver(operation.trackerId)?.let { operation.trackerId to it }
                    ),
                    optimisticTrackerRemovals = state.optimisticTrackerRemovals - operation.trackerId,
                    retainedPublicTrackers = if (retained == null) state.retainedPublicTrackers else state.retainedPublicTrackers + (operation.trackerId to retained),
                )
            }
            is SharedAddRemoveOperation.PublicTrackerRemove -> state.copy(
                optimisticTrackerAdds = state.optimisticTrackerAdds - operation.trackerId,
                optimisticTrackerRemovals = state.optimisticTrackerRemovals + operation.trackerId,
            )
            is SharedAddRemoveOperation.DiscoverOnMapTrackerRemove -> state.copy(
                optimisticTrackerAdds = state.optimisticTrackerAdds - operation.trackerId,
                optimisticTrackerRemovals = state.optimisticTrackerRemovals + operation.trackerId,
                optimisticDiscoverOnMapRemovals = state.optimisticDiscoverOnMapRemovals + operation.trackerId,
            )
            is SharedAddRemoveOperation.IncomingGroupAccept -> {
                val retained = incomingGroupResolver(operation.groupId) ?: state.retainedIncomingGroups[operation.groupId]
                state.copy(
                    retainedIncomingGroups = if (retained == null) state.retainedIncomingGroups else state.retainedIncomingGroups + (operation.groupId to retained),
                )
            }
            is SharedAddRemoveOperation.PublicGroupAdd -> {
                val retained = publicGroupResolver(operation.groupId) ?: state.retainedPublicGroups[operation.groupId]
                state.copy(
                    retainedPublicGroups = if (retained == null) state.retainedPublicGroups else state.retainedPublicGroups + (operation.groupId to retained),
                )
            }
            is SharedAddRemoveOperation.DiscoverOnMapGroupRemove,
            is SharedAddRemoveOperation.PublicGroupRemove,
            -> state
        }
    }

    private fun <T> RepositoryResult<T>.mapToUnit(): RepositoryResult<Unit> {
        return when (this) {
            is RepositoryResult.Success -> RepositoryResult.Success(Unit)
            is RepositoryResult.Failure -> RepositoryResult.Failure(error)
        }
    }
}

