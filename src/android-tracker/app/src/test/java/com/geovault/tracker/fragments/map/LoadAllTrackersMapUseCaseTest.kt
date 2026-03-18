package com.geovault.tracker.fragments.map

import android.content.Context
import com.geovault.tracker.Group
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerCoordinatesResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LoadAllTrackersMapUseCaseTest {
    @Test
    fun execute_filtersHiddenTrackersAndGroups() {
        val trackRepo = object : MapTrackRepository {
            override suspend fun getTrackers(context: Context, forceRefresh: Boolean): List<Tracker> {
                return listOf(
                    Tracker(id = "t1", name = "T1", color = null),
                    Tracker(id = "t2", name = "T2", color = null),
                    Tracker(id = "t3", name = "T3", color = null)
                )
            }

            override suspend fun getTracker(context: Context, id: String, forceRefresh: Boolean): Tracker? = null
            override suspend fun getTrackerGeometry(context: Context, id: String, allData: Boolean): Tracker? = null
            override suspend fun getTrackerCoordinates(context: Context, id: String, allData: Boolean): TrackerCoordinatesResponse? = null
            override suspend fun getTrackersGeometry(context: Context, trackerIds: List<String>, allData: Boolean): List<Tracker> {
                return trackerIds.map { id ->
                    Tracker(
                        id = id,
                        name = id,
                        color = null,
                        geometry = com.geovault.tracker.GeoJsonLineString(
                            type = "LineString",
                            coordinates = listOf(listOf(10.0, 20.0), listOf(11.0, 21.0))
                        )
                    )
                }
            }

            override fun getTrackerFromCache(id: String): Tracker? = null
        }
        val groupRepo = object : MapGroupRepository {
            override suspend fun getGroups(context: Context, forceRefresh: Boolean): List<Group> {
                return listOf(Group(id = "g1", name = "G1", track_ids = listOf("t2")))
            }
        }
        val visibilityRepo = object : MapVisibilityRepository {
            override suspend fun getMapVisibility(context: Context): MapVisibilityResponse {
                return MapVisibilityResponse(hidden_track_ids = listOf("t1"), hidden_group_ids = listOf("g1"))
            }
        }

        val useCase = LoadAllTrackersMapUseCase(trackRepo, groupRepo, visibilityRepo)
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val snapshot = kotlinx.coroutines.runBlocking { useCase.execute(context) }

        assertEquals(listOf("t3"), snapshot.trackers.map { it.id })
        assertTrue(snapshot.coordsByTrackerId["t3"]?.isNotEmpty() == true)
    }
}

