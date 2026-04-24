package com.geovault.tracker.presentation

import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.db.QueuedLocation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerMapTrailDataCoordinatorTest {

    @Test
    fun loadSingleTrackerTrail_usesQueueFallbackWhenGeometryIsEmpty() = runBlocking {
        val fallback = listOf(
            QueuedLocation(
                id = 1L,
                trackerId = "t1",
                time = 1L,
                latitude = 1.0,
                longitude = 2.0,
                altitude = null,
                speed = null,
                bearing = null,
                accuracy = null,
                sat = null,
                prov = "queue",
                dist = null
            )
        )

        val result = TrackerMapTrailDataCoordinator.loadSingleTrackerTrail(
            trackerId = "t1",
            existingTrailMinTimeMs = null,
            loadTrackerGeometry = {
                RepositoryResult.Success(Tracker(id = "t1", name = "T1", color = null))
            },
            loadQueueTrailWithOverlay = { fallback },
            mapCoordinatesToTrail = { _, _, _, _ -> emptyList() }
        )

        assertEquals(fallback, result)
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

        assertEquals(1, result["t1"]?.size)
        assertEquals(10.0, result["t1"]?.firstOrNull()?.latitude)
        assertEquals(20.0, result["t1"]?.firstOrNull()?.longitude)
    }
}
