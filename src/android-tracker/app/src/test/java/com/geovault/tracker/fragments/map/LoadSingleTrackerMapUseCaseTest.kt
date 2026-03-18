package com.geovault.tracker.fragments.map

import android.content.Context
import com.geovault.tracker.GeoJsonLineString
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
    fun execute_returnsGeometryWhenAvailable() {
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
        val useCase = LoadSingleTrackerMapUseCase(repository)
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()

        val snapshot = runBlocking {
            useCase.execute(
                context = context,
                trackerId = "t1",
                displayedTrackerId = null,
                forceReplace = false
            )
        }

        assertNotNull(snapshot)
        assertEquals("t1", snapshot?.tracker?.id)
        assertEquals(2, snapshot?.coordinates?.size)
    }

    @Test
    fun execute_fallsBackToCoordinatesWhenGeometryMissing() {
        val repository = FakeTrackRepository(
            trackerById = mapOf("t2" to Tracker(id = "t2", name = "Tracker 2", color = null)),
            coordinatesById = mapOf(
                "t2" to TrackerCoordinatesResponse(
                    coordinates = listOf(listOf(30.0, 40.0), listOf(31.0, 41.0))
                )
            )
        )
        val useCase = LoadSingleTrackerMapUseCase(repository)
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()

        val snapshot = runBlocking {
            useCase.execute(
                context = context,
                trackerId = "t2",
                displayedTrackerId = null,
                forceReplace = true
            )
        }

        assertNotNull(snapshot)
        assertEquals("t2", snapshot?.tracker?.id)
        assertTrue(snapshot?.forceReplace == true)
        assertEquals(2, snapshot?.coordinates?.size)
    }

    private class FakeTrackRepository(
        private val geometryById: Map<String, Tracker> = emptyMap(),
        private val trackerById: Map<String, Tracker> = emptyMap(),
        private val coordinatesById: Map<String, TrackerCoordinatesResponse> = emptyMap(),
        private val cacheById: Map<String, Tracker> = emptyMap()
    ) : MapTrackRepository {
        override suspend fun getTrackers(context: Context, forceRefresh: Boolean): List<Tracker> = emptyList()
        override suspend fun getTracker(context: Context, id: String, forceRefresh: Boolean): Tracker? = trackerById[id]
        override suspend fun getTrackerGeometry(context: Context, id: String): Tracker? = geometryById[id]
        override suspend fun getTrackerCoordinates(context: Context, id: String): TrackerCoordinatesResponse? = coordinatesById[id]
        override suspend fun getTrackersGeometry(context: Context, trackerIds: List<String>, allData: Boolean): List<Tracker> = emptyList()
        override fun getTrackerFromCache(id: String): Tracker? = cacheById[id]
    }
}

