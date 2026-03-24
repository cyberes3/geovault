package com.geovault.tracker.fragments

import com.geovault.tracker.AppError
import com.geovault.tracker.AvailableToAddResponse
import com.geovault.tracker.Group
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EditSharedGroupViewModelTest {
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
    fun setHidden_persistedRoundTrip_updatesStateWithoutError() = runTest {
        val trackerRepo = FakeTrackerManagementRepository(
            initialVisibility = MapVisibilityResponse(hidden_group_ids = emptyList())
        )
        val vm = EditSharedGroupViewModel(
            trackerRepository = trackerRepo,
            groupRepository = FakeGroupManagementRepository()
        )

        vm.load()
        advanceUntilIdle()
        vm.setHidden(groupId = "g1", hidden = true)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.mapVisibility?.hidden_group_ids?.contains("g1") == true)
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun setHidden_roundTripMismatch_setsPersistenceMismatchError() = runTest {
        val trackerRepo = FakeTrackerManagementRepository(
            initialVisibility = MapVisibilityResponse(hidden_group_ids = emptyList()),
            staleAfterPatch = true
        )
        val vm = EditSharedGroupViewModel(
            trackerRepository = trackerRepo,
            groupRepository = FakeGroupManagementRepository()
        )

        vm.load()
        advanceUntilIdle()
        vm.setHidden(groupId = "g1", hidden = true)
        advanceUntilIdle()

        assertEquals(
            EditSharedGroupViewModel.VISIBILITY_PERSISTENCE_MISMATCH,
            vm.uiState.value.errorMessage
        )
    }

    private class FakeTrackerManagementRepository(
        initialVisibility: MapVisibilityResponse,
        private val staleAfterPatch: Boolean = false
    ) : TrackerManagementRepository {
        private var visibility = initialVisibility

        override suspend fun loadTrackers(forceRefresh: Boolean): RepositoryResult<List<Tracker>> =
            RepositoryResult.Success(emptyList())

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
            RepositoryResult.Success(UsersResponse(emptyList()))

        override suspend fun loadMapVisibility(forceRefresh: Boolean): RepositoryResult<MapVisibilityResponse> =
            RepositoryResult.Success(visibility)

        override suspend fun patchMapVisibility(request: MapVisibilityRequest): RepositoryResult<MapVisibilityResponse> {
            val updated = visibility.copy(hidden_group_ids = request.hidden_group_ids ?: visibility.hidden_group_ids)
            if (!staleAfterPatch) {
                visibility = updated
            }
            return RepositoryResult.Success(updated)
        }
    }

    private class FakeGroupManagementRepository : GroupManagementRepository {
        override suspend fun loadGroups(forceRefresh: Boolean): RepositoryResult<List<Group>> =
            RepositoryResult.Success(emptyList())

        override suspend fun loadGroup(groupId: String): RepositoryResult<Group> =
            RepositoryResult.Failure(AppError.NotFound)

        override suspend fun createGroup(name: String): RepositoryResult<Group> =
            RepositoryResult.Failure(AppError.Unknown)

        override suspend fun patchGroup(
            groupId: String,
            request: com.geovault.tracker.GroupPatchRequest,
            publishToStore: Boolean
        ): RepositoryResult<Group> = RepositoryResult.Failure(AppError.Unknown)

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
