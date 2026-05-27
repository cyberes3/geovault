package com.geovault.tracker.presentation

import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.AppError
import com.geovault.tracker.db.QueuedLocation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerMapTrailDataCoordinatorTest {

    @Test
    fun loadSingleTrackerTrail_emptyServerGeometryMeansEmptyHistory() = runBlocking {
        val fallback = listOf(
            point("t1", latitude = 1.0, longitude = 2.0)
        )

        val result = TrackerMapTrailDataCoordinator.loadSingleTrackerTrail(
            trackerId = "t1",
            existingTrailMinTimeMs = null,
            loadTrackerGeometry = {
                RepositoryResult.Success(Tracker(id = "t1", name = "T1", color = null))
            },
            loadQueueTrail = { fallback },
            mapCoordinatesToTrail = { _, _, _, _ -> emptyList() }
        )

        assertEquals(emptyList<QueuedLocation>(), result.trailsByTracker["t1"])
        assertEquals(setOf("t1"), result.authoritativeTrackerIds)
    }

    @Test
    fun loadSingleTrackerTrail_usesQueueFallbackWhenGeometryRequestFails() = runBlocking {
        val fallback = listOf(point("t1", latitude = 1.0, longitude = 2.0))

        val result = TrackerMapTrailDataCoordinator.loadSingleTrackerTrail(
            trackerId = "t1",
            existingTrailMinTimeMs = null,
            loadTrackerGeometry = {
                RepositoryResult.Failure(AppError.Network)
            },
            loadQueueTrail = { fallback },
            mapCoordinatesToTrail = { _, _, _, _ -> emptyList() }
        )

        assertEquals(fallback, result.trailsByTracker["t1"])
        assertEquals(emptySet<String>(), result.authoritativeTrackerIds)
    }

    @Test
    fun loadTrailsForTrackerIds_returnsMappedTrailsOnSuccess() = runBlocking {
        val result = TrackerMapTrailDataCoordinator.loadTrailsForTrackerIds(
            trackerIds = listOf("t1"),
            existingTrailMinTimeMsByTracker = emptyMap(),
            loadTrackersGeometry = {
                RepositoryResult.Success(
                    listOf(
                        Tracker(
                            id = "t1",
                            name = "T1",
                            color = null,
                            geometry = com.geovault.tracker.GeoJsonLineString(
                                type = "LineString",
                                coordinates = listOf(listOf(20.0, 10.0))
                            )
                        )
                    )
                )
            },
            loadQueueTrail = { trackerId ->
                listOf(point(trackerId, latitude = -1.0, longitude = -2.0))
            },
            mapCoordinatesToTrail = { trackerId, coordinates, _, _ ->
                coordinates.mapIndexed { index, point ->
                    QueuedLocation(
                        id = -(index + 1L),
                        trackerId = trackerId,
                        time = index.toLong(),
                        latitude = point[1],
                        longitude = point[0],
                        altitude = null,
                        speed = null,
                        bearing = null,
                        accuracy = null,
                        sat = null,
                        prov = "mapped",
                        dist = null
                    )
                }
            }
        )

        assertEquals(1, result.trailsByTracker["t1"]?.size)
        assertEquals(10.0, result.trailsByTracker["t1"]?.firstOrNull()?.latitude)
        assertEquals(20.0, result.trailsByTracker["t1"]?.firstOrNull()?.longitude)
        assertEquals(setOf("t1"), result.authoritativeTrackerIds)
    }

    @Test
    fun loadTrailsForTrackerIds_usesQueueFallbackForBulkFailure() = runBlocking {
        val result = TrackerMapTrailDataCoordinator.loadTrailsForTrackerIds(
            trackerIds = listOf("t1", "t2"),
            existingTrailMinTimeMsByTracker = emptyMap(),
            loadTrackersGeometry = {
                RepositoryResult.Failure(AppError.Network)
            },
            loadQueueTrail = { trackerId ->
                listOf(point(trackerId, latitude = 3.0, longitude = 4.0))
            },
            mapCoordinatesToTrail = { _, _, _, _ -> emptyList() }
        )

        assertEquals(setOf("t1", "t2"), result.trailsByTracker.keys)
        assertEquals(3.0, result.trailsByTracker["t1"]?.firstOrNull()?.latitude)
        assertEquals(4.0, result.trailsByTracker["t2"]?.firstOrNull()?.longitude)
        assertEquals(emptySet<String>(), result.authoritativeTrackerIds)
    }

    @Test
    fun loadTrailsForTrackerIds_usesQueueFallbackForMissingTrackerInBulkResponse() = runBlocking {
        val result = TrackerMapTrailDataCoordinator.loadTrailsForTrackerIds(
            trackerIds = listOf("t1", "t2"),
            existingTrailMinTimeMsByTracker = emptyMap(),
            loadTrackersGeometry = {
                RepositoryResult.Success(
                    listOf(
                        Tracker(
                            id = "t1",
                            name = "T1",
                            color = null,
                            geometry = com.geovault.tracker.GeoJsonLineString(
                                type = "LineString",
                                coordinates = listOf(listOf(20.0, 10.0))
                            )
                        )
                    )
                )
            },
            loadQueueTrail = { trackerId ->
                listOf(point(trackerId, latitude = 5.0, longitude = 6.0))
            },
            mapCoordinatesToTrail = { trackerId, coordinates, _, _ ->
                coordinates.map { point(trackerId, latitude = it[1], longitude = it[0]) }
            }
        )

        assertEquals(10.0, result.trailsByTracker["t1"]?.firstOrNull()?.latitude)
        assertEquals(5.0, result.trailsByTracker["t2"]?.firstOrNull()?.latitude)
        assertEquals(setOf("t1"), result.authoritativeTrackerIds)
    }

    private fun point(
        trackerId: String,
        latitude: Double,
        longitude: Double,
    ): QueuedLocation {
        return QueuedLocation(
            id = 1L,
            trackerId = trackerId,
            time = 1L,
            latitude = latitude,
            longitude = longitude,
            altitude = null,
            speed = null,
            bearing = null,
            accuracy = null,
            sat = null,
            prov = "queue",
            dist = null
        )
    }
}
