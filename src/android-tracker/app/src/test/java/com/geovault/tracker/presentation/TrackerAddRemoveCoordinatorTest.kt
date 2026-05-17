package com.geovault.tracker.presentation

import com.geovault.tracker.AvailableToAddItem
import com.geovault.tracker.AvailableToAddResponse
import com.geovault.tracker.AppError
import com.geovault.tracker.Group
import com.geovault.tracker.GroupPatchRequest
import com.geovault.tracker.MapVisibilityRequest
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerCheckRequest
import com.geovault.tracker.TrackerCoordinatesResponse
import com.geovault.tracker.TrackerCreateRequest
import com.geovault.tracker.TrackerSettingsRequest
import com.geovault.tracker.UsersResponse
import com.geovault.tracker.data.GroupManagementRepository
import com.geovault.tracker.data.TrackerManagementRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerAddRemoveCoordinatorTest {

    private val coordinator = TrackerAddRemoveCoordinator(
        trackerRepository = FakeTrackerManagementRepository(),
        groupRepository = FakeGroupManagementRepository(),
    )

    @Test
    fun keyPolicy_returnsStableKeysAndPhases() {
        val addOp = SharedAddRemoveOperation.PublicTrackerAdd("t-1")
        val removeOp = SharedAddRemoveOperation.PublicTrackerRemove("t-1")

        assertEquals("public-tracker-t-1", TrackerAddRemoveKeyPolicy.sharedMutationKey(addOp))
        assertEquals(SharedMutationPhase.PENDING_ADD, TrackerAddRemoveKeyPolicy.sharedMutationPhase(addOp))
        assertEquals("public-remove-tracker-t-1", TrackerAddRemoveKeyPolicy.sharedMutationKey(removeOp))
        assertEquals(SharedMutationPhase.PENDING_REMOVE, TrackerAddRemoveKeyPolicy.sharedMutationPhase(removeOp))
    }

    @Test
    fun beginSharedMutation_incomingAddSetsPendingRetentionAndOptimistic() {
        val state = SharedUiState(
            availableToAdd = AvailableToAddResponse(
                shared_with_me = listOf(AvailableToAddItem(id = "t-1", name = "Tracker 1"))
            )
        )

        val result = coordinator.beginSharedMutation(
            state = state,
            operation = SharedAddRemoveOperation.IncomingTrackerAdd("t-1"),
            optimisticTrackerResolver = { trackerId ->
                Tracker(id = trackerId, name = "Tracker 1", color = null, is_owner = false, visibility = "shared")
            },
            incomingTrackerResolver = { trackerId -> state.availableToAdd?.shared_with_me?.firstOrNull { it.id == trackerId } },
            incomingGroupResolver = { null },
            publicTrackerResolver = { null },
            publicGroupResolver = { null },
        )

        assertTrue(result.started)
        assertEquals("incoming-tracker-t-1", result.key)
        assertTrue(result.state.pendingOps.containsKey("incoming-tracker-t-1"))
        assertTrue(result.state.optimisticTrackerAdds.containsKey("t-1"))
        assertTrue(result.state.retainedIncomingTrackers.containsKey("t-1"))
    }

    @Test
    fun applyFailure_rollsBackIncomingAddRetentionAndOptimistic() {
        val state = SharedUiState(
            optimisticTrackerAdds = mapOf(
                "t-1" to Tracker(id = "t-1", name = "Tracker 1", color = null, is_owner = false, visibility = "shared")
            ),
            retainedIncomingTrackers = mapOf(
                "t-1" to AvailableToAddItem(id = "t-1", name = "Tracker 1")
            ),
        )

        val next = coordinator.applyFailure(state, SharedAddRemoveOperation.IncomingTrackerAdd("t-1"))

        assertFalse(next.optimisticTrackerAdds.containsKey("t-1"))
        assertFalse(next.retainedIncomingTrackers.containsKey("t-1"))
    }

    @Test
    fun applySuccess_discoverRemoveClearsOptimisticAndRetention() {
        val state = SharedUiState(
            optimisticTrackerRemovals = setOf("t-1"),
            optimisticDiscoverOnMapRemovals = setOf("t-1"),
            retainedIncomingTrackers = mapOf(
                "t-1" to AvailableToAddItem(id = "t-1", name = "Tracker 1")
            ),
        )

        val next = coordinator.applySuccess(state, SharedAddRemoveOperation.DiscoverOnMapTrackerRemove("t-1"))

        assertFalse(next.optimisticTrackerRemovals.contains("t-1"))
        assertFalse(next.optimisticDiscoverOnMapRemovals.contains("t-1"))
        assertFalse(next.retainedIncomingTrackers.containsKey("t-1"))
    }

    @Test
    fun groupPickerPendingLifecycle_isIdempotent() {
        val initial = emptySet<String>()
        val first = coordinator.tryBeginGroupPickerAdd(initial, "t-1")
        val second = coordinator.tryBeginGroupPickerAdd(first.second, "t-1")

        assertTrue(first.first)
        assertFalse(second.first)
        assertEquals(setOf("t-1"), first.second)
        assertEquals(emptySet<String>(), coordinator.settleGroupPickerAdd(first.second, "t-1"))
    }

    @Test
    fun executeIncomingGroupAccept_returnsAcceptedGroupFromRepository() = runBlocking {
        val acceptedGroup = Group(
            id = "g-1",
            name = "Shared Group",
            visibility = "shared",
            is_owner = false,
            is_accepted = true,
            track_ids = listOf("t-1", "t-2"),
        )
        val coordinatorWithAccept = TrackerAddRemoveCoordinator(
            trackerRepository = FakeTrackerManagementRepository(),
            groupRepository = FakeGroupManagementRepository(acceptGroupResult = RepositoryResult.Success(acceptedGroup)),
        )

        val result = coordinatorWithAccept.executeIncomingGroupAccept("g-1")

        assertTrue(result is RepositoryResult.Success)
        assertEquals(acceptedGroup, (result as RepositoryResult.Success).data)
    }
}

private class FakeTrackerManagementRepository : TrackerManagementRepository {
    override suspend fun loadTrackers(forceRefresh: Boolean): RepositoryResult<List<Tracker>> = RepositoryResult.Failure(AppError.Unknown)
    override suspend fun loadAvailableToAdd(forceRefresh: Boolean): RepositoryResult<AvailableToAddResponse> = RepositoryResult.Failure(AppError.Unknown)
    override suspend fun loadTracker(trackerId: String): RepositoryResult<Tracker> = RepositoryResult.Failure(AppError.Unknown)
    override suspend fun loadTrackerGeometry(trackerId: String): RepositoryResult<Tracker> = RepositoryResult.Failure(AppError.Unknown)
    override suspend fun loadTrackerCoordinates(trackerId: String): RepositoryResult<TrackerCoordinatesResponse> = RepositoryResult.Failure(AppError.Unknown)
    override suspend fun loadTrackersGeometry(trackerIds: List<String>): RepositoryResult<List<Tracker>> = RepositoryResult.Failure(AppError.Unknown)
    override suspend fun createTracker(request: TrackerCreateRequest): RepositoryResult<Tracker> = RepositoryResult.Failure(AppError.Unknown)
    override suspend fun updateTrackerSettings(trackerId: String, request: TrackerSettingsRequest, publishToStore: Boolean): RepositoryResult<Tracker> = RepositoryResult.Failure(AppError.Unknown)
    override suspend fun deleteTracker(trackerId: String): RepositoryResult<Unit> = RepositoryResult.Failure(AppError.Unknown)
    override suspend fun clearTrackerHistory(trackerId: String): RepositoryResult<Unit> = RepositoryResult.Failure(AppError.Unknown)
    override suspend fun leaveShareWithMe(trackerId: String): RepositoryResult<Unit> = RepositoryResult.Failure(AppError.Unknown)
    override suspend fun unsubscribeTracker(trackerId: String): RepositoryResult<Unit> = RepositoryResult.Failure(AppError.Unknown)
    override suspend fun subscribeTracker(trackerId: String): RepositoryResult<Tracker> = RepositoryResult.Failure(AppError.Unknown)
    override suspend fun checkTracker(request: TrackerCheckRequest): RepositoryResult<Boolean> = RepositoryResult.Failure(AppError.Unknown)
    override fun getTrackerFromCache(trackerId: String): Tracker? = null
    override fun clearSelectedTrackerCaches() = Unit
    override suspend fun fetchTrackerKml(trackerId: String): RepositoryResult<ByteArray> = RepositoryResult.Failure(AppError.Unknown)
    override suspend fun loadUsers(): RepositoryResult<UsersResponse> = RepositoryResult.Failure(AppError.Unknown)
    override suspend fun loadMapVisibility(forceRefresh: Boolean): RepositoryResult<MapVisibilityResponse> = RepositoryResult.Failure(AppError.Unknown)
    override suspend fun patchMapVisibility(request: MapVisibilityRequest): RepositoryResult<MapVisibilityResponse> = RepositoryResult.Failure(AppError.Unknown)
    override suspend fun clearHiddenItems(targetTypes: List<String>?): RepositoryResult<Unit> = RepositoryResult.Failure(AppError.Unknown)
}

private class FakeGroupManagementRepository(
    private val acceptGroupResult: RepositoryResult<Group> = RepositoryResult.Failure(AppError.Unknown),
) : GroupManagementRepository {
    override suspend fun loadGroups(forceRefresh: Boolean): RepositoryResult<List<Group>> = RepositoryResult.Failure(AppError.Unknown)
    override suspend fun loadGroup(groupId: String): RepositoryResult<Group> = RepositoryResult.Failure(AppError.Unknown)
    override suspend fun createGroup(name: String): RepositoryResult<Group> = RepositoryResult.Failure(AppError.Unknown)
    override suspend fun patchGroup(groupId: String, request: GroupPatchRequest, publishToStore: Boolean): RepositoryResult<Group> = RepositoryResult.Failure(AppError.Unknown)
    override suspend fun deleteGroup(groupId: String): RepositoryResult<Unit> = RepositoryResult.Failure(AppError.Unknown)
    override suspend fun addGroupTrack(groupId: String, trackId: String): RepositoryResult<Group> = RepositoryResult.Failure(AppError.Unknown)
    override suspend fun removeGroupTrack(groupId: String, trackId: String): RepositoryResult<Group> = RepositoryResult.Failure(AppError.Unknown)
    override suspend fun leaveGroup(groupId: String): RepositoryResult<Unit> = RepositoryResult.Failure(AppError.Unknown)
    override suspend fun acceptGroupShare(groupId: String): RepositoryResult<Group> = acceptGroupResult
}

