package com.geovault.tracker.fragments

import com.geovault.tracker.AppError
import com.geovault.tracker.AvailableToAddResponse
import com.geovault.tracker.GeoJsonLineString
import com.geovault.tracker.MapVisibilityRequest
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerCheckRequest
import com.geovault.tracker.TrackerCreateRequest
import com.geovault.tracker.TrackerSettingsRequest
import com.geovault.tracker.UsersResponse
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
class EditTrackerViewModelTest {
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
    fun enableWorldShare_usesProvidedTrackerIdWhenFormIdBlank() = runTest {
        val repo = FakeTrackerManagementRepository()
        val vm = EditTrackerViewModel(repo)

        vm.enableWorldShare("t123")
        advanceUntilIdle()

        assertEquals("t123", repo.lastUpdatedTrackerId)
        assertEquals(true, repo.lastTrackerSettingsRequest?.world_share_enabled)
        assertEquals(EditTrackerPhase.Ready, vm.uiState.value.phase)
        assertTrue(vm.uiState.value.form.worldShareEnabled)
        assertEquals("/live-track/share/world-share-id", vm.uiState.value.form.worldShareUrl)
    }

    @Test
    fun enableWorldShare_failureResetsToggleAndSetsError() = runTest {
        val repo = FakeTrackerManagementRepository(failOnUpdate = true)
        val vm = EditTrackerViewModel(repo)
        vm.bindInitialTracker(
            tracker = tracker(id = "t1"),
            defaultColorHex = "#1E88E5",
            isDefaultTrack = false
        )
        vm.onWorldShareEnabledChanged(true)

        vm.enableWorldShare()
        advanceUntilIdle()

        assertEquals(EditTrackerPhase.Ready, vm.uiState.value.phase)
        assertFalse(vm.uiState.value.form.worldShareEnabled)
        assertTrue(vm.uiState.value.errorMessage != null)
    }

    @Test
    fun disableWorldShare_sendsFalseAndClearsUrl() = runTest {
        val repo = FakeTrackerManagementRepository()
        val vm = EditTrackerViewModel(repo)
        vm.bindInitialTracker(
            tracker = tracker(id = "t1").copy(
                world_share_id = "world-share-id",
                world_share_url = "/live-track/share/world-share-id"
            ),
            defaultColorHex = "#1E88E5",
            isDefaultTrack = false
        )

        vm.disableWorldShare()
        advanceUntilIdle()

        assertEquals("t1", repo.lastUpdatedTrackerId)
        assertEquals(false, repo.lastTrackerSettingsRequest?.world_share_enabled)
        assertFalse(vm.uiState.value.form.worldShareEnabled)
        assertEquals(null, vm.uiState.value.form.worldShareUrl)
    }

    private fun tracker(id: String): Tracker = Tracker(
        id = id,
        name = "Tracker",
        color = null,
        settings = emptyMap(),
        geometry = GeoJsonLineString(type = "LineString", coordinates = emptyList()),
        point_params = emptyList(),
        is_owner = true,
        visibility = "private"
    )

    private class FakeTrackerManagementRepository(
        private val failOnUpdate: Boolean = false
    ) : TrackerManagementRepository {
        var lastUpdatedTrackerId: String? = null
        var lastTrackerSettingsRequest: TrackerSettingsRequest? = null

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
        ): RepositoryResult<Tracker> {
            lastUpdatedTrackerId = trackerId
            lastTrackerSettingsRequest = request
            if (failOnUpdate) {
                return RepositoryResult.Failure(AppError.Network)
            }
            val worldShareEnabled = request.world_share_enabled == true
            return RepositoryResult.Success(
                Tracker(
                    id = trackerId,
                    name = "Tracker",
                    color = null,
                    settings = emptyMap(),
                    geometry = GeoJsonLineString(type = "LineString", coordinates = emptyList()),
                    point_params = emptyList(),
                    is_owner = true,
                    visibility = "private",
                    world_share_id = if (worldShareEnabled) "world-share-id" else null,
                    world_share_url = if (worldShareEnabled) "/live-track/share/world-share-id" else null
                )
            )
        }

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
            RepositoryResult.Success(MapVisibilityResponse())

        override suspend fun patchMapVisibility(request: MapVisibilityRequest): RepositoryResult<MapVisibilityResponse> =
            RepositoryResult.Success(MapVisibilityResponse())
    }
}
