package com.geovault.tracker.presentation

import com.geovault.tracker.GeoJsonLineString
import com.geovault.tracker.Tracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TrackerMapGeometryCachePolicyTest {

    @Test
    fun stripGeometry_clearsOnlyTargetedTrackers() {
        val trackers = listOf(
            trackerWithGeometry("a"),
            trackerWithGeometry("b"),
        )
        val stripped = TrackerMapGeometryCachePolicy.stripGeometry(trackers, setOf("a"))
        assertNull(stripped[0].geometry)
        assertNull(stripped[0].point_params)
        assertNull(stripped[0].bbox)
        assertNotNull(stripped[1].geometry)
    }

    @Test
    fun stripGeometry_emptyIds_returnsSameList() {
        val trackers = listOf(trackerWithGeometry("a"))
        val result = TrackerMapGeometryCachePolicy.stripGeometry(trackers, emptySet())
        assertEquals(trackers, result)
    }

    private fun trackerWithGeometry(id: String): Tracker {
        return Tracker(
            id = id,
            name = "Tracker $id",
            color = "#00ff00",
            geometry = GeoJsonLineString(
                type = "LineString",
                coordinates = listOf(listOf(-122.0, 37.0)),
            ),
            point_params = listOf(mapOf("starttimestamp" to 1_000L)),
            bbox = listOf(-122.0, 37.0, -121.0, 38.0),
        )
    }
}
