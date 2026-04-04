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
