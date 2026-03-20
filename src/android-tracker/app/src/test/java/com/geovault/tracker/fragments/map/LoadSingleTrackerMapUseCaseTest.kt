package com.geovault.tracker.fragments.map

import android.content.Context
import com.geovault.tracker.AppError
import com.geovault.tracker.GeoJsonLineString
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerCoordinatesResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LoadSingleTrackerMapUseCaseTest {
    @Test
    fun execute_bootstrap_returnsGeometryWhenAvailable() {
        val repository = FakeTrackRepository(
            geometryById = mapOf(
                "t1" to Tracker(
                    id = "t1",
                    name = "Tracker 1",
                    color = null,
                    geometry = GeoJsonLineString(
                        type = "LineString",
                        coordinates = listOf(listOf(10.0, 20.0), listOf(11.0, 21.0))
                    )
                )
            )
        )
        val useCase = LoadSingleTrackerMapUseCase(
            runtimeRepository = repository,
            bootstrapRepository = repository
        )
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()

        val snapshot = runBlocking {
            useCase.execute(
                context = context,
                trackerId = "t1",
                displayedTrackerId = null,
                forceReplace = false,
                mode = SingleTrackerLoadMode.BOOTSTRAP
            )
        }

        assertNotNull(snapshot)
        assertEquals("t1", snapshot?.tracker?.id)
        assertEquals(2, snapshot?.coordinates?.size)
        assertEquals(listOf(false), repository.geometryAllDataRequests)
        assertEquals(listOf(false), repository.coordinatesAllDataRequests)
    }

    @Test
    fun execute_bootstrap_fallsBackToCoordinatesWhenGeometryMissing() {
        val repository = FakeTrackRepository(
            trackerById = mapOf("t2" to Tracker(id = "t2", name = "Tracker 2", color = null)),
            coordinatesById = mapOf(
                "t2" to TrackerCoordinatesResponse(
                    coordinates = listOf(listOf(30.0, 40.0), listOf(31.0, 41.0))
                )
            )
        )
        val useCase = LoadSingleTrackerMapUseCase(
            runtimeRepository = repository,
            bootstrapRepository = repository
        )
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()

        val snapshot = runBlocking {
            useCase.execute(
                context = context,
                trackerId = "t2",
                displayedTrackerId = null,
                forceReplace = true,
                mode = SingleTrackerLoadMode.BOOTSTRAP
            )
        }

        assertNotNull(snapshot)
        assertEquals("t2", snapshot?.tracker?.id)
        assertTrue(snapshot?.forceReplace == true)
        assertEquals(2, snapshot?.coordinates?.size)
        assertEquals(listOf(false), repository.coordinatesAllDataRequests)
    }

    @Test
    fun execute_bootstrap_prefersRicherCoordinatePayloadWhenGeometryIsShort() {
        val repository = FakeTrackRepository(
            geometryById = mapOf(
                "t3" to Tracker(
                    id = "t3",
                    name = "Tracker 3",
                    color = null,
                    geometry = GeoJsonLineString(
                        type = "LineString",
                        coordinates = listOf(listOf(10.0, 20.0, 1_773_891_400_000.0))
                    )
                )
            ),
            coordinatesById = mapOf(
                "t3" to TrackerCoordinatesResponse(
                    coordinates = listOf(
                        listOf(10.0, 20.0, 1_773_891_400_000.0),
                        listOf(11.0, 21.0, 1_773_891_401_000.0),
                        listOf(12.0, 22.0, 1_773_891_402_000.0)
                    )
                )
            )
        )
        val useCase = LoadSingleTrackerMapUseCase(
            runtimeRepository = repository,
            bootstrapRepository = repository
        )
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()

        val snapshot = runBlocking {
            useCase.execute(
                context = context,
                trackerId = "t3",
                displayedTrackerId = null,
                forceReplace = false,
                mode = SingleTrackerLoadMode.BOOTSTRAP
            )
        }

        assertNotNull(snapshot)
        assertEquals("t3", snapshot?.tracker?.id)
        assertEquals(3, snapshot?.coordinates?.size)
        assertEquals(listOf(false), repository.geometryAllDataRequests)
        assertEquals(listOf(false), repository.coordinatesAllDataRequests)
    }

    @Test
    fun execute_bootstrap_keepsSinglePointHistoryBaseline() {
        val repository = FakeTrackRepository(
            geometryById = mapOf(
                "t4" to Tracker(
                    id = "t4",
                    name = "Tracker 4",
                    color = null,
                    geometry = GeoJsonLineString(
                        type = "LineString",
                        coordinates = listOf(listOf(10.0, 20.0, 1_773_891_400_000.0))
                    )
                )
            )
        )
        val useCase = LoadSingleTrackerMapUseCase(
            runtimeRepository = repository,
            bootstrapRepository = repository
        )
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()

        val snapshot = runBlocking {
            useCase.execute(
                context = context,
                trackerId = "t4",
                displayedTrackerId = null,
                forceReplace = false,
                mode = SingleTrackerLoadMode.BOOTSTRAP
            )
        }

        assertNotNull(snapshot)
        assertEquals("t4", snapshot?.tracker?.id)
        assertEquals(1, snapshot?.coordinates?.size)
    }

    @Test
    fun execute_runtime_skipsGeometryEndpoint() {
        val repository = FakeTrackRepository(
            geometryById = mapOf(
                "t5" to Tracker(
                    id = "t5",
                    name = "Tracker 5",
                    color = null,
                    geometry = GeoJsonLineString(
                        type = "LineString",
                        coordinates = listOf(listOf(1.0, 2.0), listOf(3.0, 4.0))
                    )
                )
            ),
            trackerById = mapOf("t5" to Tracker(id = "t5", name = "Tracker 5", color = null)),
            coordinatesById = mapOf(
                "t5" to TrackerCoordinatesResponse(
                    coordinates = listOf(
                        listOf(10.0, 20.0, 1_773_891_400_000.0),
                        listOf(11.0, 21.0, 1_773_891_401_000.0)
                    )
                )
            )
        )
        val useCase = LoadSingleTrackerMapUseCase(
            runtimeRepository = repository,
            bootstrapRepository = repository
        )
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()

        val snapshot = runBlocking {
            useCase.execute(
                context = context,
                trackerId = "t5",
                displayedTrackerId = null,
                forceReplace = false,
                mode = SingleTrackerLoadMode.RUNTIME
            )
        }

        assertNotNull(snapshot)
        assertEquals("t5", snapshot?.tracker?.id)
        assertEquals(2, snapshot?.coordinates?.size)
        assertEquals(emptyList<Boolean>(), repository.geometryAllDataRequests)
        assertEquals(listOf(false), repository.coordinatesAllDataRequests)
    }

    private class FakeTrackRepository(
        private val geometryById: Map<String, Tracker> = emptyMap(),
        private val trackerById: Map<String, Tracker> = emptyMap(),
        private val coordinatesById: Map<String, TrackerCoordinatesResponse> = emptyMap(),
        private val cacheById: Map<String, Tracker> = emptyMap()
    ) : MapTrackRepository {
        val geometryAllDataRequests = mutableListOf<Boolean>()
        val coordinatesAllDataRequests = mutableListOf<Boolean>()
        override suspend fun getTrackers(forceRefresh: Boolean): RepositoryResult<List<Tracker>> =
            RepositoryResult.Success(emptyList())

        override suspend fun getTracker(id: String, forceRefresh: Boolean): RepositoryResult<Tracker> {
            return trackerById[id]?.let { RepositoryResult.Success(it) } ?: RepositoryResult.Failure(AppError.NotFound)
        }

        override suspend fun getTrackerGeometry(id: String): RepositoryResult<Tracker> {
            geometryAllDataRequests += false
            return geometryById[id]?.let { RepositoryResult.Success(it) } ?: RepositoryResult.Failure(AppError.NotFound)
        }

        override suspend fun getTrackerCoordinates(id: String): RepositoryResult<TrackerCoordinatesResponse> {
            coordinatesAllDataRequests += false
            return coordinatesById[id]?.let { RepositoryResult.Success(it) } ?: RepositoryResult.Failure(AppError.NotFound)
        }

        override suspend fun getTrackersCoordinates(
            trackerIds: List<String>
        ): RepositoryResult<Map<String, TrackerCoordinatesResponse>> = RepositoryResult.Success(emptyMap())

        override fun getTrackerFromCache(id: String): Tracker? = cacheById[id]
    }
}

