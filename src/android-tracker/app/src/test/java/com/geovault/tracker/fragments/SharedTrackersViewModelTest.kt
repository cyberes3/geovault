package com.geovault.tracker.fragments

import com.geovault.tracker.AppError
import com.geovault.tracker.AvailableToAddResponse
import com.geovault.tracker.GeoJsonLineString
import com.geovault.tracker.Group
import com.geovault.tracker.GroupPatchRequest
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerCheckRequest
import com.geovault.tracker.TrackerCreateRequest
import com.geovault.tracker.TrackerSettingsRequest
import com.geovault.tracker.UsersResponse
import com.geovault.tracker.data.GroupManagementRepository
import com.geovault.tracker.data.TrackerManagementStateStore
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
class SharedTrackersViewModelTest {
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
    fun refresh_filtersToAcceptedSharedGroupsAndVisibleSharedTrackers() = runTest {
        val groupedTrackId = "t-grouped"
        val hiddenTrackId = "t-hidden"
        val standaloneTrackId = "t-shared"
        val stateStore = TrackerManagementStateStore()
        val vm = SharedTrackersViewModel(
            trackerManagementRepository = FakeTrackerManagementRepository(
                trackers = listOf(
                    tracker(id = groupedTrackId, visibility = "shared"),
                    tracker(id = standaloneTrackId, visibility = "public"),
                    tracker(id = hiddenTrackId, visibility = "shared")
                ),
                stateStore = stateStore
            ),
            groupManagementRepository = FakeGroupManagementRepository(
                groups = listOf(
                    group(
                        id = "g-accepted",
                        isAccepted = true,
                        trackIds = listOf(groupedTrackId)
                    ),
                    group(
                        id = "g-pending",
                        isAccepted = false,
                        trackIds = listOf("t-pending")
                    )
                ),
                stateStore = stateStore
            ),
            trackerManagementStateStore = stateStore,
            sharedSurfaceFilterUseCase = SharedSurfaceFilterUseCase()
        )

        vm.refresh(forceRefresh = true, showLoading = true)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(listOf("g-accepted"), state.data.sharedGroups.map { it.id })
        assertEquals(listOf(standaloneTrackId, hiddenTrackId), state.data.sharedTrackers.map { it.id })
    }

    @Test
    fun refresh_setsErrorWhenRepositoryFails() = runTest {
        val stateStore = TrackerManagementStateStore()
        val vm = SharedTrackersViewModel(
            trackerManagementRepository = FakeTrackerManagementRepository(
                trackers = emptyList(),
                stateStore = stateStore
            ),
            groupManagementRepository = FakeGroupManagementRepository(
                groups = emptyList(),
                stateStore = stateStore,
                failLoad = true
            ),
            trackerManagementStateStore = stateStore,
            sharedSurfaceFilterUseCase = SharedSurfaceFilterUseCase()
        )

        vm.refresh(forceRefresh = true, showLoading = true)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.errorMessage != null)
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

    private fun group(id: String, isAccepted: Boolean, trackIds: List<String>): Group =
        Group(
            id = id,
            name = id,
            visibility = "shared",
            is_owner = false,
            is_accepted = isAccepted,
            track_ids = trackIds
        )

    private class FakeTrackerManagementRepository(
        private val trackers: List<Tracker>,
        private val stateStore: TrackerManagementStateStore
    ) : TrackerManagementRepository {
        override suspend fun loadTrackers(forceRefresh: Boolean): RepositoryResult<List<Tracker>> {
            stateStore.publishTrackers(trackers)
            return RepositoryResult.Success(trackers)
        }

        override suspend fun loadAvailableToAdd(forceRefresh: Boolean): RepositoryResult<AvailableToAddResponse> =
            RepositoryResult.Success(AvailableToAddResponse())

        override suspend fun loadTracker(trackerId: String): RepositoryResult<Tracker> =
            RepositoryResult.Failure(AppError.NotFound)

        override suspend fun loadTrackerGeometry(trackerId: String): RepositoryResult<Tracker> =
            RepositoryResult.Failure(AppError.NotFound)

        override suspend fun createTracker(request: TrackerCreateRequest): RepositoryResult<Tracker> =
            RepositoryResult.Failure(AppError.Unknown)

        override suspend fun updateTrackerSettings(
            trackerId: String,
            request: TrackerSettingsRequest,
            publishToStore: Boolean
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

        override suspend fun loadMapVisibility(forceRefresh: Boolean): RepositoryResult<com.geovault.tracker.MapVisibilityResponse> =
            RepositoryResult.Success(com.geovault.tracker.MapVisibilityResponse())

        override suspend fun patchMapVisibility(request: com.geovault.tracker.MapVisibilityRequest): RepositoryResult<com.geovault.tracker.MapVisibilityResponse> =
            RepositoryResult.Failure(AppError.Unknown)
    }

    private class FakeGroupManagementRepository(
        private val groups: List<Group>,
        private val stateStore: TrackerManagementStateStore,
        private val failLoad: Boolean = false
    ) : GroupManagementRepository {
        override suspend fun loadGroups(forceRefresh: Boolean): RepositoryResult<List<Group>> {
            if (failLoad) return RepositoryResult.Failure(AppError.Network)
            stateStore.publishGroups(groups)
            return RepositoryResult.Success(groups)
        }

        override suspend fun loadGroup(groupId: String): RepositoryResult<Group> =
            RepositoryResult.Failure(AppError.NotFound)

        override suspend fun createGroup(name: String): RepositoryResult<Group> =
            RepositoryResult.Failure(AppError.Unknown)

        override suspend fun patchGroup(
            groupId: String,
            request: GroupPatchRequest,
            publishToStore: Boolean
        ): RepositoryResult<Group> =
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
