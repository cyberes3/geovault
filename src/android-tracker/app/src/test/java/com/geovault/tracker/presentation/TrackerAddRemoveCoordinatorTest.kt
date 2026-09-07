package com.geovault.tracker.presentation

import com.geovault.common.net.GeoVaultApiFailure
import com.geovault.tracker.AvailableToAddItem
import com.geovault.tracker.AvailableToAddResponse
import com.geovault.tracker.Group
import com.geovault.tracker.GroupPatchRequest
import com.geovault.tracker.MapVisibilityRequest
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerCheckRequest
import com.geovault.tracker.TrackerCoordinatesResponse
import com.geovault.tracker.TrackerCreateRequest
import com.geovault.tracker.TrackerSettingsRequest
import com.geovault.tracker.UsersResponse
import com.geovault.tracker.data.GroupManagementRepository
import com.geovault.tracker.data.TrackerManagementRepository
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
}

private fun unknownApiFailure(): Nothing =
    throw GeoVaultApiFailure(httpCode = null, serverMessage = "Unknown")

private class FakeTrackerManagementRepository : TrackerManagementRepository {
    override suspend fun loadTrackers(forceRefresh: Boolean): List<Tracker> = unknownApiFailure()
    override suspend fun loadAvailableToAdd(forceRefresh: Boolean): AvailableToAddResponse = unknownApiFailure()
    override suspend fun loadTracker(trackerId: String): Tracker = unknownApiFailure()
    override suspend fun loadTrackerGeometry(trackerId: String): Tracker = unknownApiFailure()
    override suspend fun loadTrackerCoordinates(trackerId: String): TrackerCoordinatesResponse = unknownApiFailure()
    override suspend fun loadTrackersGeometry(trackerIds: List<String>): List<Tracker> = unknownApiFailure()
    override suspend fun createTracker(request: TrackerCreateRequest): Tracker = unknownApiFailure()
    override suspend fun updateTrackerSettings(trackerId: String, request: TrackerSettingsRequest, publishToStore: Boolean): Tracker = unknownApiFailure()
    override suspend fun deleteTracker(trackerId: String) = unknownApiFailure()
    override suspend fun clearTrackerHistory(trackerId: String) = unknownApiFailure()
    override suspend fun leaveShareWithMe(trackerId: String) = unknownApiFailure()
    override suspend fun unsubscribeTracker(trackerId: String) = unknownApiFailure()
    override suspend fun subscribeTracker(trackerId: String): Tracker = unknownApiFailure()
    override suspend fun checkTracker(request: TrackerCheckRequest): Boolean = unknownApiFailure()
    override fun getTrackerFromCache(trackerId: String): Tracker? = null
    override fun clearSelectedTrackerCaches() = Unit
    override suspend fun fetchTrackerKml(trackerId: String): ByteArray = unknownApiFailure()
    override suspend fun loadUsers(): UsersResponse = unknownApiFailure()
    override suspend fun loadMapVisibility(forceRefresh: Boolean): MapVisibilityResponse = unknownApiFailure()
    override suspend fun patchMapVisibility(request: MapVisibilityRequest): MapVisibilityResponse = unknownApiFailure()
    override suspend fun clearHiddenItems(targetTypes: List<String>?) = unknownApiFailure()
}

private class FakeGroupManagementRepository : GroupManagementRepository {
    override suspend fun loadGroups(forceRefresh: Boolean): List<Group> = unknownApiFailure()
    override suspend fun loadGroup(groupId: String): Group = unknownApiFailure()
    override suspend fun createGroup(name: String): Group = unknownApiFailure()
    override suspend fun patchGroup(groupId: String, request: GroupPatchRequest, publishToStore: Boolean): Group = unknownApiFailure()
    override suspend fun deleteGroup(groupId: String) = unknownApiFailure()
    override suspend fun addGroupTrack(groupId: String, trackId: String): Group = unknownApiFailure()
    override suspend fun removeGroupTrack(groupId: String, trackId: String): Group = unknownApiFailure()
    override suspend fun leaveGroup(groupId: String) = unknownApiFailure()
    override suspend fun acceptGroupShare(groupId: String): Group = unknownApiFailure()
}

