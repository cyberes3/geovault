package com.geovault.tracker.fragments.map

import com.geovault.tracker.Group
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.RepositoryResult
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
            override suspend fun getTrackers(forceRefresh: Boolean): RepositoryResult<List<Tracker>> {
                return RepositoryResult.Success(
                    listOf(
                    Tracker(id = "t1", name = "T1", color = null),
                    Tracker(id = "t2", name = "T2", color = null),
                    Tracker(id = "t3", name = "T3", color = null)
                )
                )
            }

            override suspend fun getTracker(id: String, forceRefresh: Boolean): RepositoryResult<Tracker> =
                RepositoryResult.Failure(com.geovault.tracker.AppError.NotFound)

            override suspend fun getTrackerGeometry(id: String, allData: Boolean): RepositoryResult<Tracker> =
                RepositoryResult.Failure(com.geovault.tracker.AppError.NotFound)

            override suspend fun getTrackerCoordinates(id: String, allData: Boolean): RepositoryResult<TrackerCoordinatesResponse> =
                RepositoryResult.Failure(com.geovault.tracker.AppError.NotFound)

            override suspend fun getTrackersCoordinates(
                trackerIds: List<String>,
                allData: Boolean
            ): RepositoryResult<Map<String, TrackerCoordinatesResponse>> {
                return RepositoryResult.Success(
                    trackerIds.associateWith {
                        TrackerCoordinatesResponse(
                            coordinates = listOf(listOf(10.0, 20.0), listOf(11.0, 21.0))
                        )
                    }
                )
            }

            override fun getTrackerFromCache(id: String): Tracker? = null
        }
        val groupRepo = object : MapGroupRepository {
            override suspend fun getGroups(forceRefresh: Boolean): RepositoryResult<List<Group>> {
                return RepositoryResult.Success(listOf(Group(id = "g1", name = "G1", track_ids = listOf("t2"))))
            }
        }
        val visibilityRepo = object : MapVisibilityRepository {
            override suspend fun getMapVisibility(): RepositoryResult<MapVisibilityResponse> {
                return RepositoryResult.Success(
                    MapVisibilityResponse(hidden_track_ids = listOf("t1"), hidden_group_ids = listOf("g1"))
                )
            }
        }

        val useCase = LoadAllTrackersMapUseCase(trackRepo, groupRepo, visibilityRepo)
        val result = kotlinx.coroutines.runBlocking { useCase.execute() }
        val snapshot = result.snapshot

        assertEquals(listOf("t3"), snapshot.trackers.map { it.id })
        assertTrue(snapshot.coordsByTrackerId["t3"]?.isNotEmpty() == true)
        assertTrue(!result.hadFailures)
    }
}

