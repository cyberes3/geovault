package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapStateTransformsRemoteMarkersTest {

    @Test
    fun buildRenderState_addsRemoteMarkersOnlyForActiveStreamIds() {
        val render = TrackerMapStateTransforms.buildRenderState(
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            trail = listOf(
                QueuedLocation(
                    trackerId = "active",
                    time = 10L,
                    latitude = 1.0,
                    longitude = 1.0,
                    altitude = null,
                    speed = null,
                    bearing = null,
                    accuracy = null
                )
            ),
            runtime = TrackingRuntimeSnapshot(),
            remoteLastPoints = mapOf(
                "active" to remotePoint("active", 5.0, 6.0),
                "inactive" to remotePoint("inactive", 7.0, 8.0)
            ),
            activeStreamedTrackerIds = setOf("active")
        )

        val markerIds = render.points.map { it.id }
        assertTrue("remote-active marker missing", markerIds.contains("remote-active"))
        assertTrue("remote-inactive marker should be filtered", !markerIds.contains("remote-inactive"))
        val remoteActive = render.points.first { it.id == "remote-active" }
        assertEquals(
            TrackerMapIconIds.simpleForColor(TrackerMapIconIds.DEFAULT_COLOR_HEX),
            remoteActive.iconImageId
        )
    }

    @Test
    fun buildRenderState_withNoActiveStreamIds_ignoresRemotePoints() {
        val render = TrackerMapStateTransforms.buildRenderState(
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            trail = emptyList(),
            runtime = TrackingRuntimeSnapshot(),
            remoteLastPoints = mapOf("r1" to remotePoint("r1", 1.0, 2.0)),
            activeStreamedTrackerIds = emptySet()
        )

        assertEquals(0, render.points.count { it.id.startsWith("remote-") })
    }

    @Test
    fun buildRenderState_allQueue_marksSelectedTrackerWithFullChevronVariant() {
        val render = TrackerMapStateTransforms.buildRenderState(
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            trail = emptyList(),
            runtime = TrackingRuntimeSnapshot(),
            allQueueTrailsByTracker = mapOf(
                "t1" to listOf(
                    QueuedLocation(
                        trackerId = "t1",
                        time = 1L,
                        latitude = 10.0,
                        longitude = 20.0,
                        altitude = null,
                        speed = null,
                        bearing = null,
                        accuracy = null
                    ),
                    QueuedLocation(
                        trackerId = "t1",
                        time = 2L,
                        latitude = 10.001,
                        longitude = 20.002,
                        altitude = null,
                        speed = null,
                        bearing = null,
                        accuracy = null
                    )
                )
            ),
            trackerColorById = mapOf("t1" to "#00FF00"),
            selectedMapTrackerId = "t1",
        )

        val marker = render.points.first { it.id == "remote-t1" }
        assertEquals(TrackerMapIconIds.selectedForColor("#00FF00"), marker.iconImageId)
    }

    @Test
    fun buildRenderState_allQueue_respectsTrackerRenderOrder() {
        val render = TrackerMapStateTransforms.buildRenderState(
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            trail = emptyList(),
            runtime = TrackingRuntimeSnapshot(),
            allQueueTrailsByTracker = mapOf(
                "b" to listOf(
                    QueuedLocation(trackerId = "b", time = 1L, latitude = 1.0, longitude = 1.0, altitude = null, speed = null, bearing = null, accuracy = null),
                    QueuedLocation(trackerId = "b", time = 2L, latitude = 1.1, longitude = 1.1, altitude = null, speed = null, bearing = null, accuracy = null)
                ),
                "a" to listOf(
                    QueuedLocation(trackerId = "a", time = 1L, latitude = 2.0, longitude = 2.0, altitude = null, speed = null, bearing = null, accuracy = null),
                    QueuedLocation(trackerId = "a", time = 2L, latitude = 2.1, longitude = 2.1, altitude = null, speed = null, bearing = null, accuracy = null)
                ),
            ),
            trackerRenderOrder = listOf("b", "a"),
        )

        assertEquals(listOf("remote-b", "remote-a"), render.points.map { it.id })
    }

    @Test
    fun buildRenderState_allQueue_usesTrackerDisplayNameForMarkerTitle() {
        val render = TrackerMapStateTransforms.buildRenderState(
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            trail = emptyList(),
            runtime = TrackingRuntimeSnapshot(),
            allQueueTrailsByTracker = mapOf(
                "tracker-1" to listOf(
                    QueuedLocation(trackerId = "tracker-1", time = 1L, latitude = 10.0, longitude = 20.0, altitude = null, speed = null, bearing = null, accuracy = null),
                    QueuedLocation(trackerId = "tracker-1", time = 2L, latitude = 10.1, longitude = 20.1, altitude = null, speed = null, bearing = null, accuracy = null),
                )
            ),
            trackerDisplayNameById = mapOf("tracker-1" to "Delta"),
        )

        val marker = render.points.first { it.id == "remote-tracker-1" }
        assertEquals("Delta", marker.title)
    }

    @Test
    fun buildRenderState_remoteFallback_usesTrackerIdWhenDisplayNameMissing() {
        val render = TrackerMapStateTransforms.buildRenderState(
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            trail = emptyList(),
            runtime = TrackingRuntimeSnapshot(),
            remoteLastPoints = mapOf("remote-a" to remotePoint("remote-a", 5.0, 6.0)),
            activeStreamedTrackerIds = setOf("remote-a"),
            trackerDisplayNameById = emptyMap(),
        )

        val marker = render.points.first { it.id == "remote-remote-a" }
        assertEquals("remote-a", marker.title)
    }

    @Test
    fun buildRenderState_allQueue_remoteMarkersEmitMatchingAccuracyPolygons() {
        val render = TrackerMapStateTransforms.buildRenderState(
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            trail = emptyList(),
            runtime = TrackingRuntimeSnapshot(),
            remoteLastPoints = mapOf(
                "r1" to remotePoint("r1", 5.0, 6.0),
                "r2" to remotePoint("r2", 7.0, 8.0),
            ),
            activeStreamedTrackerIds = setOf("r1", "r2"),
            streamedAccuracyByTrackerId = mapOf(
                "r1" to 4f,
                "r2" to 7f,
            ),
        )

        assertTrue(render.points.any { it.id == "remote-r1" })
        assertTrue(render.points.any { it.id == "remote-r2" })
        assertTrue(render.polygons.any { it.id == "accuracy-r1" })
        assertTrue(render.polygons.any { it.id == "accuracy-r2" })
    }

    private fun remotePoint(trackId: String, lat: Double, lon: Double): TrackPointEvent {
        return TrackPointEvent(
            source = TrackPointSource.REMOTE_STREAM,
            trackId = trackId,
            lon = lon,
            lat = lat,
            timestampMs = 1234L,
            accuracyMeters = 4f
        )
    }
}
