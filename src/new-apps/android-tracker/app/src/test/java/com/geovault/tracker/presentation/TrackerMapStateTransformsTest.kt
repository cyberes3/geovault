package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapStateTransformsTest {

    @Test
    fun groupPlaceholderMode_emptyGeoJsonState() {
        val trail = listOf(
            QueuedLocation(
                time = 1L,
                latitude = 1.0,
                longitude = 2.0,
                altitude = null,
                speed = null,
                bearing = null,
                accuracy = null,
            ),
        )
        val st = TrackerMapStateTransforms.buildRenderState(
            TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            trail,
            TrackingRuntimeSnapshot(lastTrackedLatitude = 3.0, lastTrackedLongitude = 4.0),
        )
        assertTrue(st.points.isEmpty())
        assertTrue(st.lines.isEmpty())
    }

    @Test
    fun twoPointTrail_hasLineAndLastMarkerWithBearing() {
        val trail = listOf(
            QueuedLocation(
                time = 1L,
                latitude = 10.0,
                longitude = 20.0,
                altitude = null,
                speed = null,
                bearing = null,
                accuracy = null,
            ),
            QueuedLocation(
                time = 2L,
                latitude = 10.1,
                longitude = 20.1,
                altitude = null,
                speed = null,
                bearing = 33.5f,
                accuracy = null,
            ),
        )
        val st = TrackerMapStateTransforms.buildRenderState(
            TrackerMapDisplayMode.SINGLE_SESSION,
            trail,
            TrackingRuntimeSnapshot(selectedTrackerName = "T1"),
        )
        assertEquals(1, st.lines.size)
        assertEquals("tracker-trail", st.lines.first().id)
        assertEquals(1, st.points.size)
        assertEquals("last-fix", st.points.first().id)
        assertEquals(33.5f, st.points.first().iconRotationDegrees)
        assertEquals("T1", st.points.first().title)
    }

    @Test
    fun emptyTrail_runtimeLastKnown_stillShowsMarker() {
        val st = TrackerMapStateTransforms.buildRenderState(
            TrackerMapDisplayMode.ALL_QUEUE,
            trail = emptyList(),
            runtime = TrackingRuntimeSnapshot(
                lastTrackedLatitude = -33.0,
                lastTrackedLongitude = 151.0,
            ),
        )
        assertTrue(st.lines.isEmpty())
        assertEquals(1, st.points.size)
        assertEquals(-33.0, st.points.first().latitude, 1e-9)
    }

    @Test
    fun effectiveTrail_singleSession_filtersBacklogBySessionBoundary() {
        val trail = listOf(
            QueuedLocation(id = 1L, time = 1L, latitude = 1.0, longitude = 1.0, altitude = null, speed = null, bearing = null, accuracy = null),
            QueuedLocation(id = 2L, time = 2L, latitude = 2.0, longitude = 2.0, altitude = null, speed = null, bearing = null, accuracy = null),
            QueuedLocation(id = 3L, time = 3L, latitude = 3.0, longitude = 3.0, altitude = null, speed = null, bearing = null, accuracy = null),
        )
        val filtered = TrackerMapStateTransforms.effectiveTrail(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            trail = trail,
            runtime = TrackingRuntimeSnapshot(sessionVisibleBoundaryId = 2L)
        )
        assertEquals(listOf(3L), filtered.map { it.id })
    }

    @Test
    fun effectiveTrail_allQueue_keepsBacklogAndCurrentSession() {
        val trail = listOf(
            QueuedLocation(id = 1L, time = 1L, latitude = 1.0, longitude = 1.0, altitude = null, speed = null, bearing = null, accuracy = null),
            QueuedLocation(id = 3L, time = 3L, latitude = 3.0, longitude = 3.0, altitude = null, speed = null, bearing = null, accuracy = null),
        )
        val filtered = TrackerMapStateTransforms.effectiveTrail(
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            trail = trail,
            runtime = TrackingRuntimeSnapshot(sessionVisibleBoundaryId = 2L)
        )
        assertEquals(listOf(1L, 3L), filtered.map { it.id })
    }

    @Test
    fun trailBounds_singlePoint_degenerateBounds() {
        val trail = listOf(
            QueuedLocation(
                time = 1L,
                latitude = 5.0,
                longitude = 6.0,
                altitude = null,
                speed = null,
                bearing = null,
                accuracy = null,
            ),
        )
        val b = TrackerMapStateTransforms.trailBounds(trail)
        assertNotNull(b)
        assertEquals(5.0, b!!.latitudeNorth, 1e-9)
        assertEquals(5.0, b.latitudeSouth, 1e-9)
    }

    @Test
    fun trailBounds_empty_null() {
        assertNull(TrackerMapStateTransforms.trailBounds(emptyList()))
    }
}
