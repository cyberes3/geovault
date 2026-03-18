package com.geovault.tracker.fragments.map

import android.content.Context
import com.geovault.tracker.GeoJsonLineString
import com.geovault.tracker.Group
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerCoordinatesResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LoadGroupMapUseCaseTest {
    @Test
    fun execute_returnsEmptySnapshotWhenGroupHasNoTrackers() {
        val repository = FakeTrackRepository()
        val useCase = LoadGroupMapUseCase(repository)
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()

        val snapshot = runBlocking {
            useCase.execute(
                context = context,
                group = Group(id = "g1", name = "Group 1", track_ids = emptyList()),
                zoomToTrackerId = null
            )
        }

        assertTrue(snapshot.trackers.isEmpty())
        assertTrue(snapshot.coordsByTrackerId.isEmpty())
        assertFalse(snapshot.fitBounds)
    }

    @Test
    fun execute_filtersToGroupTrackersAndAppliesZoomTarget() {
        val repository = FakeTrackRepository(
            trackers = listOf(
                Tracker(id = "a", name = "A", color = null),
                Tracker(id = "b", name = "B", color = null),
                Tracker(id = "c", name = "C", color = null)
            ),
            geometryById = mapOf(
                "a" to listOf(listOf(10.0, 20.0), listOf(11.0, 21.0)),
                "c" to listOf(listOf(30.0, 40.0), listOf(31.0, 41.0))
            )
        )
        val useCase = LoadGroupMapUseCase(repository)
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()

        val snapshot = runBlocking {
            useCase.execute(
                context = context,
                group = Group(id = "g2", name = "Group 2", track_ids = listOf("a", "c")),
                zoomToTrackerId = "c"
            )
        }

        assertEquals(listOf("a", "c"), snapshot.trackers.map { it.id })
        assertEquals(2, snapshot.coordsByTrackerId.size)
        assertTrue(snapshot.fitBounds)
        assertEquals("c", snapshot.fitToTrackerId)
    }

    private class FakeTrackRepository(
        private val trackers: List<Tracker> = emptyList(),
        private val geometryById: Map<String, List<List<Double>>> = emptyMap()
    ) : MapTrackRepository {
        override suspend fun getTrackers(context: Context, forceRefresh: Boolean): List<Tracker> = trackers
        override suspend fun getTracker(context: Context, id: String, forceRefresh: Boolean): Tracker? = null
        override suspend fun getTrackerGeometry(context: Context, id: String, allData: Boolean): Tracker? = null
        override suspend fun getTrackerCoordinates(context: Context, id: String, allData: Boolean): TrackerCoordinatesResponse? = null
        override suspend fun getTrackersGeometry(context: Context, trackerIds: List<String>, allData: Boolean): List<Tracker> {
            return trackerIds.map { id ->
                Tracker(
                    id = id,
                    name = id,
                    color = null,
                    geometry = GeoJsonLineString(
                        type = "LineString",
                        coordinates = geometryById[id] ?: emptyList()
                    )
                )
            }
        }

        override fun getTrackerFromCache(id: String): Tracker? = null
    }
}

