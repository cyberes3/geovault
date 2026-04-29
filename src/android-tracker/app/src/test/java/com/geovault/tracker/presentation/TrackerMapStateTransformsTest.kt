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
    fun groupPlaceholderMode_rendersRemoteMarkersAndGroupLines() {
        val trail = listOf(
            QueuedLocation(
                trackerId = "g1",
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
            remoteLastPoints = mapOf(
                "g1" to com.geovault.tracker.policy.TrackPointEvent(
                    source = com.geovault.tracker.policy.TrackPointSource.REMOTE_STREAM,
                    trackId = "g1",
                    lon = 2.2,
                    lat = 1.1,
                    timestampMs = 1L
                )
            ),
            activeStreamedTrackerIds = setOf("g1"),
            allQueueTrailsByTracker = mapOf(
                "g1" to listOf(
                    QueuedLocation(trackerId = "g1", time = 1L, latitude = 1.0, longitude = 2.0, altitude = null, speed = null, bearing = null, accuracy = null),
                    QueuedLocation(trackerId = "g1", time = 2L, latitude = 1.001, longitude = 2.001, altitude = null, speed = null, bearing = null, accuracy = null)
                )
            ),
        )
        assertEquals(1, st.lines.size)
        assertEquals(1, st.points.size)
        assertEquals("remote-g1", st.points.first().id)
    }

    @Test
    fun twoPointTrail_hasLineAndLastMarkerWithBearing() {
        val trail = listOf(
            QueuedLocation(
                trackerId = "t1",
                time = 1L,
                latitude = 10.0,
                longitude = 20.0,
                altitude = null,
                speed = null,
                bearing = null,
                accuracy = null,
            ),
            QueuedLocation(
                trackerId = "t1",
                time = 2L,
                latitude = 10.001,
                longitude = 20.001,
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
        assertTrue(st.lines.first().id.startsWith("tracker-trail-"))
        assertEquals(TrackerMapIconIds.DEFAULT_COLOR_HEX, st.lines.first().lineColorHex)
        assertEquals(1, st.points.size)
        assertEquals("last-fix", st.points.first().id)
        assertEquals(
            TrackerMapIconIds.selectedForColor(TrackerMapIconIds.DEFAULT_COLOR_HEX),
            st.points.first().iconImageId
        )
        assertEquals(45.0f, st.points.first().iconRotationDegrees)
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
        assertTrue(st.points.isEmpty())
    }

    @Test
    fun effectiveTrail_singleSession_filtersBacklogBySessionBoundary() {
        val trail = listOf(
            QueuedLocation(id = 1L, trackerId = "t1", time = 1L, latitude = 1.0, longitude = 1.0, altitude = null, speed = null, bearing = null, accuracy = null),
            QueuedLocation(id = 2L, trackerId = "t1", time = 2L, latitude = 2.0, longitude = 2.0, altitude = null, speed = null, bearing = null, accuracy = null),
            QueuedLocation(id = 3L, trackerId = "t1", time = 3L, latitude = 3.0, longitude = 3.0, altitude = null, speed = null, bearing = null, accuracy = null),
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
            QueuedLocation(id = 1L, trackerId = "t1", time = 1L, latitude = 1.0, longitude = 1.0, altitude = null, speed = null, bearing = null, accuracy = null),
            QueuedLocation(id = 3L, trackerId = "t1", time = 3L, latitude = 3.0, longitude = 3.0, altitude = null, speed = null, bearing = null, accuracy = null),
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
                trackerId = "t1",
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

    @Test
    fun trailBounds_skipsOutOfRangeCoordinates() {
        val trail = listOf(
            QueuedLocation(
                trackerId = "t1",
                time = 1L,
                latitude = 150.0,
                longitude = 40.0,
                altitude = null,
                speed = null,
                bearing = null,
                accuracy = null,
            ),
            QueuedLocation(
                trackerId = "t1",
                time = 2L,
                latitude = 10.0,
                longitude = 20.0,
                altitude = null,
                speed = null,
                bearing = null,
                accuracy = null,
            ),
        )
        val b = TrackerMapStateTransforms.trailBounds(trail)
        assertNotNull(b)
        assertEquals(10.0, b!!.latitudeNorth, 1e-9)
        assertEquals(10.0, b.latitudeSouth, 1e-9)
    }

    @Test
    fun trailBounds_allInvalid_null() {
        val trail = listOf(
            QueuedLocation(
                trackerId = "t1",
                time = 1L,
                latitude = 200.0,
                longitude = 0.0,
                altitude = null,
                speed = null,
                bearing = null,
                accuracy = null,
            ),
        )
        assertNull(TrackerMapStateTransforms.trailBounds(trail))
    }

    @Test
    fun allQueueMode_rendersPerTrackerLinesWithTrackerColors() {
        val render = TrackerMapStateTransforms.buildRenderState(
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            trail = emptyList(),
            runtime = TrackingRuntimeSnapshot(),
            allQueueTrailsByTracker = mapOf(
                "t1" to listOf(
                    QueuedLocation(trackerId = "t1", time = 1L, latitude = 1.0, longitude = 1.0, altitude = null, speed = null, bearing = null, accuracy = null),
                    QueuedLocation(trackerId = "t1", time = 2L, latitude = 1.001, longitude = 1.001, altitude = null, speed = null, bearing = null, accuracy = null)
                ),
                "t2" to listOf(
                    QueuedLocation(trackerId = "t2", time = 1L, latitude = 2.0, longitude = 2.0, altitude = null, speed = null, bearing = null, accuracy = null),
                    QueuedLocation(trackerId = "t2", time = 2L, latitude = 2.001, longitude = 2.001, altitude = null, speed = null, bearing = null, accuracy = null)
                )
            ),
            trackerColorById = mapOf("t1" to "FF0000", "t2" to "#00FF00")
        )
        assertEquals(2, render.lines.size)
        assertTrue(render.lines.any { it.id.startsWith("all-track-t1-") && it.lineColorHex == "#FF0000" })
        assertTrue(render.lines.any { it.id.startsWith("all-track-t2-") && it.lineColorHex == "#00FF00" })
    }

    @Test
    fun singleSession_usesDisplayedTrackerColorForChevronIconId() {
        val render = TrackerMapStateTransforms.buildRenderState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            trail = listOf(
                QueuedLocation(
                    trackerId = "displayed",
                    time = 1L,
                    latitude = 1.0,
                    longitude = 2.0,
                    altitude = null,
                    speed = null,
                    bearing = null,
                    accuracy = null,
                ),
                QueuedLocation(
                    trackerId = "displayed",
                    time = 2L,
                    latitude = 1.001,
                    longitude = 2.002,
                    altitude = null,
                    speed = null,
                    bearing = null,
                    accuracy = null,
                )
            ),
            runtime = TrackingRuntimeSnapshot(selectedTrackerId = "selected"),
            displayedTrackerId = "displayed",
            trackerColorById = mapOf(
                "displayed" to "#AA33CC",
                "selected" to "#00FF00",
            ),
        )

        val marker = render.points.first { it.id == "last-fix" }
        assertEquals(TrackerMapIconIds.selectedForColor("#AA33CC"), marker.iconImageId)
        assertEquals("#AA33CC", render.lines.first().lineColorHex)
    }

    @Test
    fun singleSession_emitsAccuracyPolygonWhenAccuracyPresent() {
        val render = TrackerMapStateTransforms.buildRenderState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            trail = listOf(
                QueuedLocation(
                    trackerId = "t1",
                    time = 1L,
                    latitude = 1.0,
                    longitude = 2.0,
                    altitude = null,
                    speed = null,
                    bearing = null,
                    accuracy = 12f,
                ),
                QueuedLocation(
                    trackerId = "t1",
                    time = 2L,
                    latitude = 1.001,
                    longitude = 2.001,
                    altitude = null,
                    speed = null,
                    bearing = null,
                    accuracy = 11f,
                )
            ),
            runtime = TrackingRuntimeSnapshot(),
            displayedTrackerId = "t1",
            trackerColorById = mapOf("t1" to "#3366CC"),
            streamedAccuracyMeters = 10f,
            fallbackAccuracyMeters = null,
            allowAccuracyFallback = false,
        )

        assertEquals(1, render.polygons.size)
        assertEquals("accuracy-last-fix", render.polygons.first().id)
        assertEquals("rgba(51,102,204,0.2509804)", render.polygons.first().fillColorHex)
    }

    @Test
    fun allQueue_emitsAccuracyPolygonsForAllVisibleTrackers() {
        val render = TrackerMapStateTransforms.buildRenderState(
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            trail = emptyList(),
            runtime = TrackingRuntimeSnapshot(),
            allQueueTrailsByTracker = mapOf(
                "t1" to listOf(
                    QueuedLocation(trackerId = "t1", time = 1L, latitude = 1.0, longitude = 1.0, altitude = null, speed = null, bearing = null, accuracy = 9f),
                    QueuedLocation(trackerId = "t1", time = 2L, latitude = 1.1, longitude = 1.1, altitude = null, speed = null, bearing = null, accuracy = 10f),
                ),
                "t2" to listOf(
                    QueuedLocation(trackerId = "t2", time = 1L, latitude = 2.0, longitude = 2.0, altitude = null, speed = null, bearing = null, accuracy = 18f),
                    QueuedLocation(trackerId = "t2", time = 2L, latitude = 2.1, longitude = 2.1, altitude = null, speed = null, bearing = null, accuracy = 20f),
                ),
            ),
            trackerColorById = mapOf(
                "t1" to "#AA0000",
                "t2" to "#00AA00",
            ),
            streamedAccuracyByTrackerId = mapOf(
                "t1" to 10f,
                "t2" to 20f,
            )
        )

        assertEquals(2, render.polygons.size)
        assertTrue(render.polygons.any { it.id == "accuracy-t1" })
        assertTrue(render.polygons.any { it.id == "accuracy-t2" })
    }

    @Test
    fun allQueue_fallbackAllowedForSpecificTracker_onlyRendersThatTrackerPolygon() {
        val render = TrackerMapStateTransforms.buildRenderState(
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            trail = emptyList(),
            runtime = TrackingRuntimeSnapshot(selectedTrackerId = "t2"),
            allQueueTrailsByTracker = mapOf(
                "t1" to listOf(
                    QueuedLocation(trackerId = "t1", time = 1L, latitude = 1.0, longitude = 1.0, altitude = null, speed = null, bearing = null, accuracy = null),
                    QueuedLocation(trackerId = "t1", time = 2L, latitude = 1.1, longitude = 1.1, altitude = null, speed = null, bearing = null, accuracy = null),
                ),
                "t2" to listOf(
                    QueuedLocation(trackerId = "t2", time = 1L, latitude = 2.0, longitude = 2.0, altitude = null, speed = null, bearing = null, accuracy = null),
                    QueuedLocation(trackerId = "t2", time = 2L, latitude = 2.1, longitude = 2.1, altitude = null, speed = null, bearing = null, accuracy = null),
                ),
            ),
            fallbackAccuracyByTrackerId = mapOf("t2" to 14f),
            allowAccuracyFallbackByTrackerId = setOf("t2")
        )

        assertEquals(1, render.polygons.size)
        assertEquals("accuracy-t2", render.polygons.first().id)
    }

}
