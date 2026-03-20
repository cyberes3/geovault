package com.geovault.tracker.fragments

import com.geovault.tracker.AppError
import com.geovault.tracker.AvailableToAddGroup
import com.geovault.tracker.AvailableToAddItem
import com.geovault.tracker.AvailableToAddResponse
import com.geovault.tracker.GeoJsonLineString
import com.geovault.tracker.Group
import com.geovault.tracker.GroupPatchRequest
import com.geovault.tracker.MapVisibilityRequest
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerCheckRequest
import com.geovault.tracker.TrackerCreateRequest
import com.geovault.tracker.TrackerSettingsRequest
import com.geovault.tracker.UsersResponse
import com.geovault.tracker.data.GroupManagementRepository
import com.geovault.tracker.data.TrackerManagementRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DiscoverTrackersViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun load_mapsOnMyMapAndIncomingSharedGroupsWithPendingTrackIdsCleared() = runTest {
        val vm = DiscoverTrackersViewModel(
            trackerManagementRepository = FakeTrackerManagementRepository(
                trackers = listOf(
                    tracker("t-on-map", "shared"),
                    tracker("t-standalone", "public")
                ),
                mapVisibility = MapVisibilityResponse(),
                availableToAdd = AvailableToAddResponse(
                    shared_with_me = listOf(AvailableToAddItem(id = "t-incoming", name = "Incoming")),
                    shared_with_me_groups = listOf(
                        AvailableToAddGroup(
                            id = "g-pending",
                            name = "Pending",
                            track_ids = listOf("t-should-hide")
                        )
                    )
                )
            ),
            groupManagementRepository = FakeGroupManagementRepository(
                groups = listOf(group("g-on-map", trackIds = listOf("t-on-map")))
            ),
            sharedSurfaceFilterUseCase = SharedSurfaceFilterUseCase()
        )

        vm.load(forceRefresh = true)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(listOf("g-on-map"), state.onMyMapGroups.map { it.id })
        assertEquals(listOf("t-standalone"), state.onMyMapTrackers.map { it.id })
        assertEquals(listOf("t-incoming"), state.incomingTrackers.map { it.id })
        assertEquals(emptyList<String>(), state.incomingSharedGroups.first().track_ids)
    }

    @Test
    fun load_setsErrorWhenAvailableToAddFails() = runTest {
        val vm = DiscoverTrackersViewModel(
            trackerManagementRepository = FakeTrackerManagementRepository(
                trackers = emptyList(),
                mapVisibility = MapVisibilityResponse(),
                availableToAdd = null
            ),
            groupManagementRepository = FakeGroupManagementRepository(groups = emptyList()),
            sharedSurfaceFilterUseCase = SharedSurfaceFilterUseCase()
        )
        vm.load(forceRefresh = true)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isLoading)
        assertTrue(vm.uiState.value.errorMessage != null)
    }

    private fun tracker(id: String, visibility: String): Tracker =
        Tracker(
            id = id,
            name = id,
            color = null,
            settings = emptyMap(),
            geometry = GeoJsonLineString(type = "LineString", coordinates = emptyList()),
            point_params = emptyList(),
            is_owner = false,
            visibility = visibility
        )

    private fun group(id: String, trackIds: List<String>): Group =
        Group(
            id = id,
            name = id,
            visibility = "shared",
            is_owner = false,
            is_accepted = true,
            track_ids = trackIds
        )

    private class FakeTrackerManagementRepository(
        private val trackers: List<Tracker>,
        private val mapVisibility: MapVisibilityResponse,
        private val availableToAdd: AvailableToAddResponse?
    ) : TrackerManagementRepository {
        override suspend fun loadTrackers(forceRefresh: Boolean): RepositoryResult<List<Tracker>> =
            RepositoryResult.Success(trackers)

        override suspend fun loadAvailableToAdd(forceRefresh: Boolean): RepositoryResult<AvailableToAddResponse> =
            availableToAdd?.let { RepositoryResult.Success(it) } ?: RepositoryResult.Failure(AppError.Network)

        override suspend fun loadTracker(trackerId: String): RepositoryResult<Tracker> =
            RepositoryResult.Failure(AppError.NotFound)

        override suspend fun loadTrackerGeometry(trackerId: String): RepositoryResult<Tracker> =
            RepositoryResult.Failure(AppError.NotFound)

        override suspend fun createTracker(request: TrackerCreateRequest): RepositoryResult<Tracker> =
            RepositoryResult.Failure(AppError.Unknown)

        override suspend fun updateTrackerSettings(
            trackerId: String,
            request: TrackerSettingsRequest
        ): RepositoryResult<Tracker> = RepositoryResult.Failure(AppError.Unknown)

        override suspend fun deleteTracker(trackerId: String): RepositoryResult<Unit> =
            RepositoryResult.Failure(AppError.Unknown)

        override suspend fun clearTrackerHistory(trackerId: String): RepositoryResult<Unit> =
            RepositoryResult.Failure(AppError.Unknown)

        override suspend fun leaveShareWithMe(trackerId: String): RepositoryResult<Unit> =
            RepositoryResult.Failure(AppError.Unknown)

        override suspend fun unsubscribeTracker(trackerId: String): RepositoryResult<Unit> =
            RepositoryResult.Failure(AppError.Unknown)

        override suspend fun subscribeTracker(trackerId: String): RepositoryResult<Tracker> =
            RepositoryResult.Failure(AppError.Unknown)

        override suspend fun checkTracker(request: TrackerCheckRequest): RepositoryResult<Boolean> =
            RepositoryResult.Failure(AppError.Unknown)

        override fun getTrackerFromCache(trackerId: String): Tracker? = null

        override fun clearSelectedTrackerCaches() = Unit

        override suspend fun fetchTrackerKml(trackerId: String): RepositoryResult<ByteArray> =
            RepositoryResult.Failure(AppError.Unknown)

        override suspend fun loadUsers(): RepositoryResult<UsersResponse> =
            RepositoryResult.Failure(AppError.Unknown)

        override suspend fun loadMapVisibility(forceRefresh: Boolean): RepositoryResult<MapVisibilityResponse> =
            RepositoryResult.Success(mapVisibility)

        override suspend fun patchMapVisibility(request: MapVisibilityRequest): RepositoryResult<MapVisibilityResponse> =
            RepositoryResult.Failure(AppError.Unknown)
    }

    private class FakeGroupManagementRepository(
        private val groups: List<Group>
    ) : GroupManagementRepository {
        override suspend fun loadGroups(forceRefresh: Boolean): RepositoryResult<List<Group>> =
            RepositoryResult.Success(groups)

        override suspend fun loadGroup(groupId: String): RepositoryResult<Group> =
            RepositoryResult.Failure(AppError.NotFound)

        override suspend fun createGroup(name: String): RepositoryResult<Group> =
            RepositoryResult.Failure(AppError.Unknown)

        override suspend fun patchGroup(groupId: String, request: GroupPatchRequest): RepositoryResult<Group> =
            RepositoryResult.Failure(AppError.Unknown)

        override suspend fun deleteGroup(groupId: String): RepositoryResult<Unit> =
            RepositoryResult.Failure(AppError.Unknown)

        override suspend fun addGroupTrack(groupId: String, trackId: String): RepositoryResult<Group> =
            RepositoryResult.Failure(AppError.Unknown)

        override suspend fun removeGroupTrack(groupId: String, trackId: String): RepositoryResult<Group> =
            RepositoryResult.Failure(AppError.Unknown)

        override suspend fun leaveGroup(groupId: String): RepositoryResult<Unit> =
            RepositoryResult.Failure(AppError.Unknown)

        override suspend fun acceptGroupShare(groupId: String): RepositoryResult<Group> =
            RepositoryResult.Failure(AppError.Unknown)
    }
}
