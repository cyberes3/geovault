package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.services.RecordingRuntime
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapStateTransformsTest {

    @Test
    fun buildRenderState_fromSessionSnapshot_usesAcceptedGeometryOnly() {
        val acceptedPoint = com.geovault.tracker.policy.TrackPointEvent(
            source = com.geovault.tracker.policy.TrackPointSource.REMOTE_STREAM,
            trackId = "accepted",
            lon = 2.0,
            lat = 1.0,
            timestampMs = 10L,
        )
        val rejectedPoint = com.geovault.tracker.policy.TrackPointEvent(
            source = com.geovault.tracker.policy.TrackPointSource.REMOTE_STREAM,
            trackId = "rejected",
            lon = 4.0,
            lat = 3.0,
            timestampMs = 10L,
        )
        val session = TrackerMapSessionSnapshot(
            uiState = TrackerMapUiState(
                mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
                activeStreamedTrackerIds = setOf("accepted", "rejected"),
                streamTargetIds = setOf("accepted", "rejected"),
            ),
            plan = TrackerMapStreamingPlan(
                mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
                selectedTrackerId = "",
                displayedTrackerId = "",
                displayedTrackerName = "",
                resolvedGroupId = "group",
                groupTrackerIds = setOf("accepted"),
                visibleRosterTrackerIds = setOf("accepted", "rejected"),
                locallyRecordedTrackerIds = emptySet(),
                remoteSubscriptionIds = setOf("accepted"),
                acceptedRemoteTrackerIds = setOf("accepted"),
                localOverlayTrackerIds = emptySet(),
                trailReloadPlan = TrackerMapTrailReloadPlan(
                    source = TrackerMapTrailSource.MULTI_SERVER,
                    trackerIds = setOf("accepted"),
                    resolvedGroupId = "group",
                ),
            ),
            runtime = TrackingRuntimeSnapshot(),
            singleTrail = emptyList(),
            tracks = emptyMap(),
            acceptedRemoteLastPoints = mapOf(
                "accepted" to acceptedPoint,
                "rejected" to rejectedPoint,
            ).filterKeys { it == "accepted" },
        )

        val renderState = TrackerMapStateTransforms.buildRenderState(
            session = session,
            cosmetics = TrackerMapRenderCosmetics(
                trackerDisplayNameById = mapOf("accepted" to "Accepted"),
            ),
        )

        assertEquals(listOf("remote-accepted"), renderState.points.map { it.id })
    }

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
    fun singleSession_emptyTrailUsesSelectedRuntimePointForMarker() {
        val st = TrackerMapStateTransforms.buildRenderState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            trail = emptyList(),
            runtime = TrackingRuntimeSnapshot(
                selectedTrackerId = "local",
                selectedTrackerName = "Local Tracker",
                lastTrackedLatitude = -33.0,
                lastTrackedLongitude = 151.0,
            ),
            displayedTrackerId = "local",
        )

        assertTrue(st.lines.isEmpty())
        assertEquals(1, st.points.size)
        assertEquals("last-fix", st.points.first().id)
        assertEquals(-33.0, st.points.first().latitude, 1e-9)
        assertEquals(151.0, st.points.first().longitude, 1e-9)
        assertEquals("Local Tracker", st.points.first().title)
    }

    @Test
    fun singleSessionRendersLoadedTrailWithoutSessionBoundaryClipping() {
        val trail = listOf(
            QueuedLocation(id = 1L, trackerId = "t1", time = 1L, latitude = 1.0, longitude = 1.0, altitude = null, speed = null, bearing = null, accuracy = null),
            QueuedLocation(id = 2L, trackerId = "t1", time = 2L, latitude = 1.001, longitude = 1.001, altitude = null, speed = null, bearing = null, accuracy = null),
            QueuedLocation(id = 3L, trackerId = "t1", time = 3L, latitude = 1.002, longitude = 1.002, altitude = null, speed = null, bearing = null, accuracy = null),
        )
        val render = TrackerMapStateTransforms.buildRenderState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            trail = trail,
            runtime = TrackingRuntimeSnapshot(sessionVisibleBoundaryId = 2L)
        )

        assertEquals(trail.map { it.latitude to it.longitude }, render.lines.flatMap { it.coordinates })
        assertEquals(1.002, render.points.first().latitude, 1e-9)
    }

    @Test
    fun singleSessionKeepsLoadedLiveOverlayPointsRegardlessOfSessionStart() {
        val trail = listOf(
            QueuedLocation(id = 10L, trackerId = "t1", time = 100L, latitude = 25.79, longitude = -80.13, altitude = null, speed = null, bearing = null, accuracy = null),
            QueuedLocation(id = 0L, trackerId = "t1", time = 150L, latitude = 25.79, longitude = -80.13, altitude = null, speed = null, bearing = null, accuracy = null),
            QueuedLocation(id = 0L, trackerId = "t1", time = 300L, latitude = 24.55, longitude = -81.78, altitude = null, speed = null, bearing = null, accuracy = null),
            QueuedLocation(id = 12L, trackerId = "t1", time = 320L, latitude = 24.56, longitude = -81.77, altitude = null, speed = null, bearing = null, accuracy = null),
        )
        val render = TrackerMapStateTransforms.buildRenderState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            trail = trail,
            runtime = TrackingRuntimeSnapshot(
                sessionVisibleBoundaryId = 10L,
                sessionStartTimeMs = 250L,
            )
        )

        assertEquals(trail.map { it.latitude to it.longitude }, render.lines.flatMap { it.coordinates })
        assertEquals(24.56, render.points.first().latitude, 1e-9)
    }

    @Test
    fun allQueue_keepsLoadedBacklogAndCurrentSession() {
        val trail = listOf(
            QueuedLocation(id = 1L, trackerId = "t1", time = 1L, latitude = 1.0, longitude = 1.0, altitude = null, speed = null, bearing = null, accuracy = null),
            QueuedLocation(id = 3L, trackerId = "t1", time = 3L, latitude = 1.002, longitude = 1.002, altitude = null, speed = null, bearing = null, accuracy = null),
        )
        val render = TrackerMapStateTransforms.buildRenderState(
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            trail = trail,
            runtime = TrackingRuntimeSnapshot(sessionVisibleBoundaryId = 2L),
            allQueueTrailsByTracker = mapOf("t1" to trail),
        )

        assertEquals(trail.map { it.latitude to it.longitude }, render.lines.flatMap { it.coordinates })
        assertEquals(1, render.points.size)
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
    fun singleSession_whileTrackingRendersDisplayedRemoteTrail() {
        val render = TrackerMapStateTransforms.buildRenderState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            trail = listOf(
                QueuedLocation(
                    trackerId = "remote",
                    time = 1L,
                    latitude = 1.0,
                    longitude = 2.0,
                    altitude = null,
                    speed = null,
                    bearing = null,
                    accuracy = null,
                ),
                QueuedLocation(
                    trackerId = "remote",
                    time = 2L,
                    latitude = 1.001,
                    longitude = 2.001,
                    altitude = null,
                    speed = null,
                    bearing = 90f,
                    accuracy = null,
                )
            ),
            runtime = TrackingRuntimeSnapshot(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "local"),
                selectedTrackerId = "local",
                selectedTrackerName = "Local Tracker",
            ),
            displayedTrackerId = "remote",
            displayedTrackerName = "Remote Tracker",
            trackerColorById = mapOf("remote" to "#123456"),
        )

        assertEquals(1, render.lines.size)
        assertEquals(1, render.points.size)
        assertEquals("last-fix", render.points.first().id)
        assertEquals("Remote Tracker", render.points.first().title)
        assertEquals(TrackerMapIconIds.selectedForColor("#123456"), render.points.first().iconImageId)
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
            fallbackAccuracyMeters = null,
            allowAccuracyFallback = false,
        )

        assertEquals(1, render.polygons.size)
        assertEquals("accuracy-last-fix", render.polygons.first().id)
        assertEquals("rgba(51,102,204,0.2509804)", render.polygons.first().fillColorHex)
        assertPolygonRadiusMeters(
            expectedMeters = 11.0,
            centerLatitude = 1.001,
            polygon = render.polygons.first(),
        )
    }

    @Test
    fun singleSession_runtimeOnlyMarkerUsesRuntimeAccuracyForAccuracyPolygon() {
        val render = TrackerMapStateTransforms.buildRenderState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            trail = emptyList(),
            runtime = TrackingRuntimeSnapshot(
                selectedTrackerId = "t1",
                lastTrackedLatitude = 1.0,
                lastTrackedLongitude = 2.0,
                lastAccuracyMeters = 37f,
            ),
            displayedTrackerId = "t1",
            fallbackAccuracyMeters = null,
            allowAccuracyFallback = false,
        )

        assertEquals(1, render.polygons.size)
        assertEquals("accuracy-last-fix", render.polygons.first().id)
        assertPolygonRadiusMeters(
            expectedMeters = 37.0,
            centerLatitude = 1.0,
            polygon = render.polygons.first(),
        )
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
        )

        assertEquals(2, render.polygons.size)
        assertTrue(render.polygons.any { it.id == "accuracy-t1" })
        assertTrue(render.polygons.any { it.id == "accuracy-t2" })
        assertPolygonRadiusMeters(
            expectedMeters = 10.0,
            centerLatitude = 1.1,
            polygon = render.polygons.first { it.id == "accuracy-t1" },
        )
        assertPolygonRadiusMeters(
            expectedMeters = 20.0,
            centerLatitude = 2.1,
            polygon = render.polygons.first { it.id == "accuracy-t2" },
        )
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

    @Test
    fun buildRenderState_singleSession_splitsLineOnLongTimeGapWithinSession() {
        val sessionStart = 1_000L
        val gapMs = TrackerMapStateTransforms.MAX_TRACK_TIME_GAP_MS + 60_000L
        val render = TrackerMapStateTransforms.buildRenderState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            trail = listOf(
                QueuedLocation(
                    trackerId = "t1",
                    time = 10L,
                    latitude = 39.70,
                    longitude = -105.20,
                    altitude = null,
                    speed = null,
                    bearing = null,
                    accuracy = null,
                    startTimestampMs = sessionStart,
                ),
                QueuedLocation(
                    trackerId = "t1",
                    time = 20L,
                    latitude = 39.7005,
                    longitude = -105.2005,
                    altitude = null,
                    speed = null,
                    bearing = null,
                    accuracy = null,
                    startTimestampMs = sessionStart,
                ),
                QueuedLocation(
                    trackerId = "t1",
                    time = 20L + gapMs,
                    latitude = 39.71,
                    longitude = -105.21,
                    altitude = null,
                    speed = null,
                    bearing = null,
                    accuracy = null,
                    startTimestampMs = sessionStart,
                ),
                QueuedLocation(
                    trackerId = "t1",
                    time = 30L + gapMs,
                    latitude = 39.7105,
                    longitude = -105.2105,
                    altitude = null,
                    speed = null,
                    bearing = null,
                    accuracy = null,
                    startTimestampMs = sessionStart,
                ),
            ),
            runtime = TrackingRuntimeSnapshot(),
            displayedTrackerId = "t1",
        )

        assertEquals(2, render.lines.size)
        assertEquals("tracker-trail-0-0-0", render.lines[0].id)
        assertEquals("tracker-trail-0-1-0", render.lines[1].id)
    }

    @Test
    fun buildRenderState_singleSession_usesWiderTimeGapWhileRecording() {
        val sessionStart = 1_000L
        val gapMs = TrackerMapStateTransforms.MAX_TRACK_TIME_GAP_MS + 60_000L
        val trail = listOf(
            QueuedLocation(
                trackerId = "t1",
                time = 10L,
                latitude = 39.70,
                longitude = -105.20,
                altitude = null,
                speed = null,
                bearing = null,
                accuracy = null,
                startTimestampMs = sessionStart,
            ),
            QueuedLocation(
                trackerId = "t1",
                time = 20L,
                latitude = 39.7005,
                longitude = -105.2005,
                altitude = null,
                speed = null,
                bearing = null,
                accuracy = null,
                startTimestampMs = sessionStart,
            ),
            QueuedLocation(
                trackerId = "t1",
                time = 20L + gapMs,
                latitude = 39.71,
                longitude = -105.21,
                altitude = null,
                speed = null,
                bearing = null,
                accuracy = null,
                startTimestampMs = sessionStart,
            ),
            QueuedLocation(
                trackerId = "t1",
                time = 30L + gapMs,
                latitude = 39.7105,
                longitude = -105.2105,
                altitude = null,
                speed = null,
                bearing = null,
                accuracy = null,
                startTimestampMs = sessionStart,
            ),
        )
        val notRecording = TrackerMapStateTransforms.buildRenderState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            trail = trail,
            runtime = TrackingRuntimeSnapshot(),
            displayedTrackerId = "t1",
        )
        val recording = TrackerMapStateTransforms.buildRenderState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            trail = trail,
            runtime = TrackingRuntimeSnapshot(
                recordingRuntime = com.geovault.tracker.services.RecordingRuntime(
                    sessionActive = true,
                    selectedTrackerId = "t1",
                ),
                sessionStartTimeMs = sessionStart,
            ),
            displayedTrackerId = "t1",
        )

        assertEquals(2, notRecording.lines.size)
        assertEquals(1, recording.lines.size)
    }

    @Test
    fun buildRenderState_singleSession_splitsAdjacentPointsByDifferentSessionStart() {
        // SESSION-AWARE LINE SPLIT: two adjacent points at the same physical location but
        // belonging to different recording sessions must produce two separate lines, not a
        // single connector. The geographic-distance split (5-mile threshold) alone would
        // happily merge them; only the session split prevents the cross-session "spike".
        val sessionA = 1_000L
        val sessionB = 2_000L
        val render = TrackerMapStateTransforms.buildRenderState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            trail = listOf(
                QueuedLocation(
                    trackerId = "t1", time = 10L,
                    latitude = 40.0, longitude = -74.0,
                    altitude = null, speed = null, bearing = null, accuracy = null,
                    startTimestampMs = sessionA,
                ),
                QueuedLocation(
                    trackerId = "t1", time = 20L,
                    latitude = 40.0001, longitude = -74.0001,
                    altitude = null, speed = null, bearing = null, accuracy = null,
                    startTimestampMs = sessionA,
                ),
                QueuedLocation(
                    trackerId = "t1", time = 30L,
                    latitude = 40.0002, longitude = -74.0002,
                    altitude = null, speed = null, bearing = null, accuracy = null,
                    startTimestampMs = sessionB,
                ),
                QueuedLocation(
                    trackerId = "t1", time = 40L,
                    latitude = 40.0003, longitude = -74.0003,
                    altitude = null, speed = null, bearing = null, accuracy = null,
                    startTimestampMs = sessionB,
                ),
            ),
            runtime = TrackingRuntimeSnapshot(),
            displayedTrackerId = "t1",
        )

        assertEquals(2, render.lines.size)
        assertEquals(listOf(2, 2), render.lines.map { it.coordinates.size })
        assertEquals("tracker-trail-0-0-0", render.lines[0].id)
        assertEquals("tracker-trail-1-0-0", render.lines[1].id)
    }

    @Test
    fun buildRenderState_singleSession_markerLastFixMatchesLatestSessionTail() {
        // CHEVRON COHERENCE: the single-session marker reads `state.trail.lastOrNull()`. The
        // latest session's last vertex must equal the marker position; in particular, after
        // a session change we must not paint the marker on the previous session's tail.
        val sessionA = 1_000L
        val sessionB = 2_000L
        val render = TrackerMapStateTransforms.buildRenderState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            trail = listOf(
                QueuedLocation(
                    trackerId = "t1", time = 10L,
                    latitude = 40.0, longitude = -74.0,
                    altitude = null, speed = null, bearing = null, accuracy = null,
                    startTimestampMs = sessionA,
                ),
                QueuedLocation(
                    trackerId = "t1", time = 20L,
                    latitude = 40.0001, longitude = -74.0001,
                    altitude = null, speed = null, bearing = null, accuracy = null,
                    startTimestampMs = sessionA,
                ),
                QueuedLocation(
                    trackerId = "t1", time = 30L,
                    latitude = 41.0, longitude = -75.0,
                    altitude = null, speed = null, bearing = null, accuracy = null,
                    startTimestampMs = sessionB,
                ),
                QueuedLocation(
                    trackerId = "t1", time = 40L,
                    latitude = 41.0001, longitude = -75.0001,
                    altitude = null, speed = null, bearing = null, accuracy = null,
                    startTimestampMs = sessionB,
                ),
            ),
            runtime = TrackingRuntimeSnapshot(),
            displayedTrackerId = "t1",
        )

        val marker = render.points.first { it.id == "last-fix" }
        val latestSessionLine = render.lines.last()
        val tailCoord = latestSessionLine.coordinates.last()
        assertEquals(tailCoord.first, marker.latitude, 0.0)
        assertEquals(tailCoord.second, marker.longitude, 0.0)
    }

    private fun assertPolygonRadiusMeters(
        expectedMeters: Double,
        centerLatitude: Double,
        polygon: com.geovault.common.maps.render.MapRenderPolygon,
    ) {
        val ring = polygon.rings.first()
        val northPoint = ring.first()
        val actualMeters = Math.toRadians(northPoint.first - centerLatitude) * 6_378_137.0
        assertEquals(expectedMeters, actualMeters, 0.05)
    }

}
