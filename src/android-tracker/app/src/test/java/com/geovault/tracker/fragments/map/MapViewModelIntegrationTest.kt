package com.geovault.tracker.fragments.map

import android.app.Application
import com.geovault.tracker.AppError
import com.geovault.tracker.GeoJsonLineString
import com.geovault.tracker.Group
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerCoordinatesResponse
import com.geovault.tracker.pipeline.TrackPointEvent
import com.geovault.tracker.pipeline.TrackPointSource
import com.geovault.tracker.services.LiveStreamRuntimeStateStore
import com.geovault.tracker.services.TrackingRuntimeStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelIntegrationTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        LiveStreamRuntimeStateStore.update {
            it.copy(isRunning = false, activeTrackerIds = emptySet())
        }
        TrackingRuntimeStateStore.update {
            it.copy(isRunning = false)
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        TrackingRuntimeStateStore.update { it.copy(isRunning = false) }
        LiveStreamRuntimeStateStore.update { it.copy(isRunning = false, activeTrackerIds = emptySet()) }
    }

    @Test
    fun loadAllTrackers_emitsRenderAndCameraCommands() = runTest {
        val stream = MutableSharedFlow<TrackPointEvent>(extraBufferCapacity = 8)
        val viewModel = createViewModel(stream)
        val commands = mutableListOf<MapCommand>()
        val job = launch {
            viewModel.commands.collect { command ->
                commands.add(command)
            }
        }

        advanceUntilIdle()
        viewModel.handleIntent(MapIntent.LoadAllTrackers)
        advanceUntilIdle()
        job.cancel()

        assertTrue(commands.any { it is MapCommand.RenderAllTrackers })
        assertTrue(commands.any { it is MapCommand.ApplyCameraPolicy })
    }

    @Test
    fun streamEvent_emitsApplyTrackPointCommand() = runTest {
        val stream = MutableSharedFlow<TrackPointEvent>(extraBufferCapacity = 8)
        val viewModel = createViewModel(stream)
        val commands = mutableListOf<MapCommand>()
        val job = launch {
            viewModel.commands.collect { command ->
                commands.add(command)
            }
        }

        LiveStreamRuntimeStateStore.update {
            it.copy(isRunning = true, activeTrackerIds = setOf("t1"))
        }
        viewModel.updateUiState { it.copy(mode = MapScreenMode.AllTrackers, showAllTrackers = true) }
        viewModel.startTrackPointStream()
        advanceUntilIdle()

        stream.emit(
            TrackPointEvent(
                source = TrackPointSource.REMOTE_STREAM,
                trackId = "t1",
                lon = 10.0,
                lat = 20.0,
                timestampMs = 1_000L
            )
        )
        advanceUntilIdle()
        job.cancel()

        assertTrue(commands.any { it is MapCommand.ApplyTrackPoint })
        viewModel.stopTrackPointStream()
    }

    @Test
    fun stopTrackPointStream_stopsFurtherCommandEmission() = runTest {
        val stream = MutableSharedFlow<TrackPointEvent>(extraBufferCapacity = 8)
        val viewModel = createViewModel(stream)
        val commands = mutableListOf<MapCommand>()
        val job = launch {
            viewModel.commands.collect { command ->
                commands.add(command)
            }
        }

        LiveStreamRuntimeStateStore.update {
            it.copy(isRunning = true, activeTrackerIds = setOf("t1"))
        }
        viewModel.updateUiState { it.copy(mode = MapScreenMode.AllTrackers, showAllTrackers = true) }
        viewModel.startTrackPointStream()
        advanceUntilIdle()
        viewModel.stopTrackPointStream()

        stream.emit(
            TrackPointEvent(
                source = TrackPointSource.REMOTE_STREAM,
                trackId = "t1",
                lon = 10.0,
                lat = 20.0,
                timestampMs = 1_000L
            )
        )
        advanceUntilIdle()
        job.cancel()

        assertEquals(0, commands.count { it is MapCommand.ApplyTrackPoint })
    }

    @Test
    fun streamEvent_trackingRunning_acceptsLocalGpsOnly() = runTest {
        val stream = MutableSharedFlow<TrackPointEvent>(extraBufferCapacity = 8)
        val viewModel = createViewModel(stream)
        val commands = mutableListOf<MapCommand>()
        val job = launch {
            viewModel.commands.collect { commands.add(it) }
        }

        LiveStreamRuntimeStateStore.update {
            it.copy(isRunning = true, activeTrackerIds = setOf("t1"))
        }
        TrackingRuntimeStateStore.update { it.copy(isRunning = true) }
        viewModel.updateUiState {
            it.copy(
                mode = MapScreenMode.Single,
                showAllTrackers = false,
                displayedTrackerId = "t1"
            )
        }
        viewModel.startTrackPointStream()
        advanceUntilIdle()

        stream.emit(
            TrackPointEvent(
                source = TrackPointSource.REMOTE_STREAM,
                trackId = "t1",
                lon = 10.0,
                lat = 20.0,
                timestampMs = 1_000L
            )
        )
        advanceUntilIdle()
        assertEquals(0, commands.count { it is MapCommand.ApplyTrackPoint })
        job.cancel()
    }

    private fun createViewModel(stream: MutableSharedFlow<TrackPointEvent>): MapViewModel {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        return MapViewModel(
            application = app,
            trackRepository = FakeTrackRepository(),
            groupRepository = FakeGroupRepository(),
            visibilityRepository = FakeVisibilityRepository(),
            streamingRepository = object : MapStreamingRepository {
                override val events: Flow<TrackPointEvent> = stream
            }
        )
    }

    private class FakeTrackRepository : MapTrackRepository {
        override suspend fun getTrackers(forceRefresh: Boolean): RepositoryResult<List<Tracker>> {
            return RepositoryResult.Success(
                listOf(
                Tracker(id = "t1", name = "T1", color = null),
                Tracker(id = "t2", name = "T2", color = null)
            )
            )
        }

        override suspend fun getTracker(id: String, forceRefresh: Boolean): RepositoryResult<Tracker> {
            return RepositoryResult.Success(Tracker(id = id, name = id, color = null))
        }

        override suspend fun getTrackerGeometry(id: String, allData: Boolean): RepositoryResult<Tracker> {
            return RepositoryResult.Success(Tracker(
                id = id,
                name = id,
                color = null,
                geometry = GeoJsonLineString(
                    type = "LineString",
                    coordinates = listOf(listOf(1.0, 2.0), listOf(3.0, 4.0))
                )
            ))
        }

        override suspend fun getTrackerCoordinates(id: String, allData: Boolean): RepositoryResult<TrackerCoordinatesResponse> {
            return RepositoryResult.Success(
                TrackerCoordinatesResponse(coordinates = listOf(listOf(1.0, 2.0), listOf(3.0, 4.0)))
            )
        }

        override suspend fun getTrackersGeometry(
            trackerIds: List<String>,
            allData: Boolean
        ): RepositoryResult<List<Tracker>> {
            return RepositoryResult.Success(trackerIds.map { id ->
                Tracker(
                    id = id,
                    name = id,
                    color = null,
                    geometry = GeoJsonLineString(
                        type = "LineString",
                        coordinates = listOf(listOf(10.0, 20.0), listOf(11.0, 21.0))
                    )
                )
            })
        }

        override fun getTrackerFromCache(id: String): Tracker? = null
    }

    private class FakeGroupRepository : MapGroupRepository {
        override suspend fun getGroups(forceRefresh: Boolean): RepositoryResult<List<Group>> =
            RepositoryResult.Success(emptyList())
    }

    private class FakeVisibilityRepository : MapVisibilityRepository {
        override suspend fun getMapVisibility(): RepositoryResult<MapVisibilityResponse> =
            RepositoryResult.Success(MapVisibilityResponse(hidden_track_ids = emptyList(), hidden_group_ids = emptyList()))
    }
}

