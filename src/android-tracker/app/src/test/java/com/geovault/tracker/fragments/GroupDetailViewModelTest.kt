package com.geovault.tracker.fragments

import com.geovault.tracker.AppError
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
import com.geovault.tracker.data.DefaultGroupTrackerEligibilityUseCase
import com.geovault.tracker.data.GroupManagementRepository
import com.geovault.tracker.data.TrackerManagementRepository
import com.geovault.tracker.data.TrackerManagementStateStore
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
class GroupDetailViewModelTest {
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
    fun addRemoveDraftTrackers_updatesLocallyWithoutNetworkCalls() = runTest {
        val stateStore = TrackerManagementStateStore()
        val initialGroup = group(trackIds = listOf("t1"))
        val trackers = listOf(
            tracker("t1", owner = true),
            tracker("t2", owner = true)
        )
        val groupRepo = FakeGroupManagementRepository(initialGroup, stateStore)
        val vm = GroupDetailViewModel(
            groupRepository = groupRepo,
            trackerRepository = FakeTrackerManagementRepository(trackers, stateStore),
            eligibilityUseCase = DefaultGroupTrackerEligibilityUseCase(),
            stateStore = stateStore
        )

        vm.load(initialGroup.id)
        advanceUntilIdle()
        vm.addDraftTracker("t2")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.draftTrackIds.contains("t2"))
        assertEquals(listOf("t1", "t2"), vm.uiState.value.draftGroupTrackers.map { it.id })
        assertTrue(groupRepo.patchCalls.isEmpty())

        vm.removeDraftTracker("t1")
        advanceUntilIdle()

        assertEquals(setOf("t2"), vm.uiState.value.draftTrackIds)
        assertEquals(listOf("t2"), vm.uiState.value.draftGroupTrackers.map { it.id })
        assertTrue(groupRepo.patchCalls.isEmpty())
        assertTrue(vm.hasUnsavedMembershipChanges())
    }

    @Test
    fun saveGroup_commitsFinalDraftTrackIdsOnce() = runTest {
        val stateStore = TrackerManagementStateStore()
        val initialGroup = group(trackIds = listOf("t1"))
        val trackers = listOf(
            tracker("t1", owner = true),
            tracker("t2", owner = true)
        )
        val groupRepo = FakeGroupManagementRepository(initialGroup, stateStore)
        val vm = GroupDetailViewModel(
            groupRepository = groupRepo,
            trackerRepository = FakeTrackerManagementRepository(trackers, stateStore),
            eligibilityUseCase = DefaultGroupTrackerEligibilityUseCase(),
            stateStore = stateStore
        )

        vm.load(initialGroup.id)
        advanceUntilIdle()
        vm.addDraftTracker("t2")
        vm.removeDraftTracker("t1")
        advanceUntilIdle()

        vm.saveGroup()
        advanceUntilIdle()

        assertEquals(1, groupRepo.patchCalls.size)
        assertEquals(listOf("t2"), groupRepo.patchCalls.single().add_track_ids)
        assertEquals(listOf("t1"), groupRepo.patchCalls.single().remove_track_ids)
        assertEquals(GroupDetailPhase.Saved, vm.uiState.value.phase)
        assertFalse(vm.hasUnsavedChanges())
        assertFalse(vm.hasUnsavedMembershipChanges())
    }

    @Test
    fun discardDraftMembership_restoresInitialTrackIds() = runTest {
        val stateStore = TrackerManagementStateStore()
        val initialGroup = group(trackIds = listOf("t1"))
        val trackers = listOf(
            tracker("t1", owner = true),
            tracker("t2", owner = true)
        )
        val vm = GroupDetailViewModel(
            groupRepository = FakeGroupManagementRepository(initialGroup, stateStore),
            trackerRepository = FakeTrackerManagementRepository(trackers, stateStore),
            eligibilityUseCase = DefaultGroupTrackerEligibilityUseCase(),
            stateStore = stateStore
        )

        vm.load(initialGroup.id)
        advanceUntilIdle()
        vm.addDraftTracker("t2")
        advanceUntilIdle()
        assertTrue(vm.hasUnsavedMembershipChanges())

        vm.discardDraftMembership()
        advanceUntilIdle()

        assertEquals(setOf("t1"), vm.uiState.value.draftTrackIds)
        assertEquals(listOf("t1"), vm.uiState.value.draftGroupTrackers.map { it.id })
        assertFalse(vm.hasUnsavedMembershipChanges())
    }

    @Test
    fun enableWorldShare_patchesWorldShareAndUpdatesForm() = runTest {
        val stateStore = TrackerManagementStateStore()
        val initialGroup = group(trackIds = listOf("t1"))
        val trackers = listOf(tracker("t1", owner = true))
        val groupRepo = FakeGroupManagementRepository(initialGroup, stateStore)
        val vm = GroupDetailViewModel(
            groupRepository = groupRepo,
            trackerRepository = FakeTrackerManagementRepository(trackers, stateStore),
            eligibilityUseCase = DefaultGroupTrackerEligibilityUseCase(),
            stateStore = stateStore
        )

        vm.load(initialGroup.id)
        advanceUntilIdle()
        vm.enableWorldShare()
        advanceUntilIdle()

        assertEquals(true, groupRepo.patchCalls.last().world_share_enabled)
        assertEquals(GroupDetailPhase.Ready, vm.uiState.value.phase)
        assertTrue(vm.uiState.value.form.worldShareEnabled)
        assertEquals("/live-track/share/world-share-id", vm.uiState.value.form.worldShareUrl)
    }

    @Test
    fun disableWorldShare_patchesWorldShareFalseAndClearsUrl() = runTest {
        val stateStore = TrackerManagementStateStore()
        val initialGroup = group(trackIds = listOf("t1")).copy(
            world_share_id = "world-share-id",
            world_share_url = "/live-track/share/world-share-id"
        )
        val trackers = listOf(tracker("t1", owner = true))
        val groupRepo = FakeGroupManagementRepository(initialGroup, stateStore)
        val vm = GroupDetailViewModel(
            groupRepository = groupRepo,
            trackerRepository = FakeTrackerManagementRepository(trackers, stateStore),
            eligibilityUseCase = DefaultGroupTrackerEligibilityUseCase(),
            stateStore = stateStore
        )

        vm.load(initialGroup.id)
        advanceUntilIdle()
        vm.disableWorldShare()
        advanceUntilIdle()

        assertEquals(false, groupRepo.patchCalls.last().world_share_enabled)
        assertEquals(GroupDetailPhase.Ready, vm.uiState.value.phase)
        assertFalse(vm.uiState.value.form.worldShareEnabled)
        assertEquals(null, vm.uiState.value.form.worldShareUrl)
    }

    private fun tracker(
        id: String,
        owner: Boolean,
        allowReshare: Boolean = false,
        visibility: String = "private"
    ): Tracker = Tracker(
        id = id,
        name = id,
        color = null,
        settings = mapOf("allow_group_reshare" to allowReshare),
        geometry = GeoJsonLineString(type = "LineString", coordinates = emptyList()),
        point_params = emptyList(),
        is_owner = owner,
        visibility = visibility
    )

    private fun group(trackIds: List<String>): Group = Group(
        id = "g1",
        name = "Group One",
        visibility = "shared",
        is_owner = true,
        is_accepted = true,
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
            trackers.firstOrNull { it.id == trackerId }?.let {
                stateStore.publishTracker(it)
                RepositoryResult.Success(it)
            } ?: RepositoryResult.Failure(AppError.NotFound)

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

        override fun getTrackerFromCache(trackerId: String): Tracker? = trackers.firstOrNull { it.id == trackerId }

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

    private class FakeGroupManagementRepository(
        private var group: Group,
        private val stateStore: TrackerManagementStateStore
    ) : GroupManagementRepository {
        val patchCalls = mutableListOf<GroupPatchRequest>()
        val patchPublishFlags = mutableListOf<Boolean>()

        override suspend fun loadGroups(forceRefresh: Boolean): RepositoryResult<List<Group>> {
            stateStore.publishGroups(listOf(group))
            return RepositoryResult.Success(listOf(group))
        }

        override suspend fun loadGroup(groupId: String): RepositoryResult<Group> {
            stateStore.publishGroup(group)
            return RepositoryResult.Success(group)
        }

        override suspend fun createGroup(name: String): RepositoryResult<Group> =
            RepositoryResult.Failure(AppError.Unknown)

        override suspend fun patchGroup(
            groupId: String,
            request: GroupPatchRequest,
            publishToStore: Boolean
        ): RepositoryResult<Group> {
            patchCalls.add(request)
            patchPublishFlags.add(publishToStore)
            val nextTrackIds = group.track_ids.orEmpty().toMutableSet()
            request.remove_track_ids.orEmpty().forEach { nextTrackIds.remove(it) }
            request.add_track_ids.orEmpty().forEach { nextTrackIds.add(it) }
            group = group.copy(
                name = request.name ?: group.name,
                hidden_in_list = request.hidden_in_list ?: group.hidden_in_list,
                visibility = request.visibility ?: group.visibility,
                shared_with_emails = request.shared_with_emails ?: group.shared_with_emails,
                world_share_id = when (request.world_share_enabled) {
                    true -> "world-share-id"
                    false -> null
                    null -> group.world_share_id
                },
                world_share_url = when (request.world_share_enabled) {
                    true -> "/live-track/share/world-share-id"
                    false -> null
                    null -> group.world_share_url
                },
                track_ids = nextTrackIds.toList()
            )
            stateStore.publishGroup(group, emitEvent = publishToStore)
            return RepositoryResult.Success(group)
        }

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
