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

    @Test
    fun save_roundTripMatch_emitsSaved() = runTest {
        val initial = tracker(id = "t1").copy(settings = mapOf("recent_data_window" to "1min"))
        val repo = FakeTrackerManagementRepository(initialTrackers = listOf(initial))
        val vm = EditTrackerViewModel(repo)
        vm.bindInitialTracker(
            tracker = initial,
            defaultColorHex = "#1E88E5",
            isDefaultTrack = false
        )
        vm.onNameChanged("Updated Tracker")
        vm.onRecentDataWindowChanged("1h")

        vm.save()
        advanceUntilIdle()

        assertEquals(EditTrackerPhase.Saved, vm.uiState.value.phase)
        assertEquals("Updated Tracker", vm.uiState.value.form.name)
        assertEquals("1h", vm.uiState.value.form.recentDataWindow)
    }

    @Test
    fun save_roundTripMismatch_keepsReadyAndSetsMismatchError() = runTest {
        val initial = tracker(id = "t1").copy(settings = mapOf("recent_data_window" to "1min"))
        val repo = FakeTrackerManagementRepository(
            initialTrackers = listOf(initial),
            staleAfterUpdate = true
        )
        val vm = EditTrackerViewModel(repo)
        vm.bindInitialTracker(
            tracker = initial,
            defaultColorHex = "#1E88E5",
            isDefaultTrack = false
        )
        vm.onRecentDataWindowChanged("1h")

        vm.save()
        advanceUntilIdle()

        assertEquals(EditTrackerPhase.Ready, vm.uiState.value.phase)
        assertEquals(EditTrackerViewModel.SAVE_PERSISTENCE_MISMATCH, vm.uiState.value.errorMessage)
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
        private val failOnUpdate: Boolean = false,
        initialTrackers: List<Tracker> = emptyList(),
        private val staleAfterUpdate: Boolean = false
    ) : TrackerManagementRepository {
        var lastUpdatedTrackerId: String? = null
        var lastTrackerSettingsRequest: TrackerSettingsRequest? = null
        private val trackerStore: MutableMap<String, Tracker> = initialTrackers.associateBy { it.id }.toMutableMap()

        override suspend fun loadTrackers(forceRefresh: Boolean): RepositoryResult<List<Tracker>> =
            RepositoryResult.Success(emptyList())

        override suspend fun loadAvailableToAdd(forceRefresh: Boolean): RepositoryResult<AvailableToAddResponse> =
            RepositoryResult.Success(AvailableToAddResponse())

        override suspend fun loadTracker(trackerId: String): RepositoryResult<Tracker> =
            trackerStore[trackerId]?.let { RepositoryResult.Success(it) } ?: RepositoryResult.Failure(AppError.NotFound)

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
            val base = trackerStore[trackerId] ?: defaultTracker(trackerId)
            val updated = applyRequest(base, request)
            if (!staleAfterUpdate) {
                trackerStore[trackerId] = updated
            }
            return RepositoryResult.Success(updated)
        }

        private fun defaultTracker(id: String): Tracker {
            return Tracker(
                id = id,
                name = "Tracker",
                color = null,
                settings = emptyMap(),
                geometry = GeoJsonLineString(type = "LineString", coordinates = emptyList()),
                point_params = emptyList(),
                is_owner = true,
                visibility = "private"
            )
        }

        private fun applyRequest(base: Tracker, request: TrackerSettingsRequest): Tracker {
            val settings = (base.settings ?: emptyMap()).toMutableMap()
            if (request.recent_data_window != null) {
                settings["recent_data_window"] = request.recent_data_window
            }
            if (request.hidden_in_list != null) {
                settings["hidden_in_list"] = request.hidden_in_list
            }
            if (request.allow_group_reshare != null) {
                settings["allow_group_reshare"] = request.allow_group_reshare
            }
            val worldShareEnabled = request.world_share_enabled ?: (!base.world_share_url.isNullOrBlank())
            return base.copy(
                name = request.name ?: base.name,
                color = request.color ?: base.color,
                settings = settings,
                visibility = request.visibility ?: base.visibility,
                share_params_with_recipients = request.share_params_with_recipients ?: base.share_params_with_recipients,
                share_params_with_world = request.share_params_with_world ?: base.share_params_with_world,
                shared_with_emails = request.shared_with_emails ?: base.shared_with_emails,
                world_share_id = if (worldShareEnabled) "world-share-id" else null,
                world_share_url = if (worldShareEnabled) "/live-track/share/world-share-id" else null
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
