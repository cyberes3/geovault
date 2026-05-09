package com.geovault.tracker.presentation

import com.geovault.tracker.Tracker
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.services.RecordingRuntime
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TrackerMapLastPointResolverTest {

    @Test
    fun resolve_usesRosterLastPointAndUpdatedAt() {
        val updated = 1_800_000_000_000L
        val state = TrackerMapUiState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "t1",
            displayedTrackerName = "T1",
            runtime = TrackingRuntimeSnapshot(
                selectedTrackerId = "other",
            ),
        )
        val t = Tracker(
            id = "t1",
            name = "T1",
            color = null,
            last_point = listOf(-10.0, 20.0, 0.0),
            updated_at = updated,
        )
        val p = TrackerMapLastPointResolver.resolve(state, "t1", t, acceptedRemoteTrackerIds = emptySet())
        assertNotNull(p)
        assertEquals(-10.0, p!!.longitude, 0.0)
        assertEquals(20.0, p.latitude, 0.0)
        assertEquals(updated, p.lastUpdatedMs)
    }

    @Test
    fun resolve_normalizesRosterSecondsAndPrefersLastPointTimestamp() {
        val state = TrackerMapUiState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "t1",
            runtime = TrackingRuntimeSnapshot(selectedTrackerId = "other"),
        )
        val t = Tracker(
            id = "t1",
            name = "T1",
            color = null,
            last_point = listOf(-10.0, 20.0, 1_710_000_001.0),
            updated_at = 1_710_000_002L,
        )

        val p = TrackerMapLastPointResolver.resolve(state, "t1", t, acceptedRemoteTrackerIds = emptySet())

        assertNotNull(p)
        assertEquals(1_710_000_001_000L, p!!.lastUpdatedMs)
    }

    @Test
    fun resolve_emptyTrackerId_returnsNull() {
        val p = TrackerMapLastPointResolver.resolve(TrackerMapUiState(), "  ", null, acceptedRemoteTrackerIds = emptySet())
        assertNull(p)
    }

    @Test
    fun resolve_runningSelectedTracker_prefersLocalRuntimePoint() {
        val state = TrackerMapUiState(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            runtime = TrackingRuntimeSnapshot(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "selected"),
                selectedTrackerId = "selected",
                lastTrackedLatitude = 30.0,
                lastTrackedLongitude = 40.0,
                lastTrackedTimestampMs = 2_000L,
                lastAccuracyMeters = 5f,
            ),
        )
        val tracker = Tracker(
            id = "selected",
            name = "Selected",
            color = null,
            last_point = listOf(-10.0, 20.0, 0.0),
            updated_at = 1_000L,
        )

        val p = TrackerMapLastPointResolver.resolve(state, "selected", tracker, acceptedRemoteTrackerIds = emptySet())

        assertNotNull(p)
        assertEquals(30.0, p!!.latitude, 0.0)
        assertEquals(40.0, p.longitude, 0.0)
        assertEquals(2_000L, p.lastUpdatedMs)
        assertEquals(5f, p.accuracyMeters)
    }

    @Test
    fun resolve_prefersTrailTailWhenNewerThanRemoteHead() {
        val state = TrackerMapUiState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "t1",
            runtime = TrackingRuntimeSnapshot(selectedTrackerId = "other"),
            trail = listOf(queued("t1", time = 2_000L, latitude = 30.0, longitude = 40.0)),
            remoteLastPoints = mapOf("t1" to remotePoint("t1", timestampMs = 1_000L, lat = 20.0, lon = 10.0)),
        )

        val p = TrackerMapLastPointResolver.resolve(state, "t1", null, acceptedRemoteTrackerIds = setOf("t1"))

        assertNotNull(p)
        assertEquals(30.0, p!!.latitude, 0.0)
        assertEquals(40.0, p.longitude, 0.0)
        assertEquals(2_000L, p.lastUpdatedMs)
    }

    @Test
    fun resolve_prefersRemoteHeadWhenNewerThanTrailTail() {
        val state = TrackerMapUiState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "t1",
            runtime = TrackingRuntimeSnapshot(selectedTrackerId = "other"),
            trail = listOf(queued("t1", time = 1_000L, latitude = 30.0, longitude = 40.0)),
            remoteLastPoints = mapOf("t1" to remotePoint("t1", timestampMs = 2_000L, lat = 20.0, lon = 10.0)),
        )

        val p = TrackerMapLastPointResolver.resolve(state, "t1", null, acceptedRemoteTrackerIds = setOf("t1"))

        assertNotNull(p)
        assertEquals(20.0, p!!.latitude, 0.0)
        assertEquals(10.0, p.longitude, 0.0)
        assertEquals(2_000L, p.lastUpdatedMs)
    }

    @Test
    fun resolveRenderedMarkerPoint_singleSessionPrefersRenderedTrailTailOverNewerRemoteHead() {
        val state = TrackerMapUiState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "t1",
            runtime = TrackingRuntimeSnapshot(selectedTrackerId = "other"),
            trail = listOf(queued("t1", time = 1_000L, latitude = 30.0, longitude = 40.0, accuracy = 12f)),
            remoteLastPoints = mapOf(
                "t1" to remotePoint("t1", timestampMs = 2_000L, lat = 20.0, lon = 10.0, accuracyMeters = 99f),
            ),
        )

        val p = TrackerMapLastPointResolver.resolveRenderedMarkerPoint(
            state = state,
            trackerId = "t1",
            tracker = null,
            acceptedRemoteTrackerIds = setOf("t1"),
        )

        assertNotNull(p)
        assertEquals(30.0, p!!.latitude, 0.0)
        assertEquals(40.0, p.longitude, 0.0)
        assertEquals(1_000L, p.lastUpdatedMs)
        assertEquals(12f, p.accuracyMeters)
    }

    @Test
    fun resolveRenderedMarkerPoint_allQueuePrefersRenderedTrailTailOverNewerRemoteHead() {
        val state = TrackerMapUiState(
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            allQueueTrailsByTracker = mapOf(
                "t1" to listOf(queued("t1", time = 1_000L, latitude = 30.0, longitude = 40.0, accuracy = 12f)),
            ),
            remoteLastPoints = mapOf(
                "t1" to remotePoint("t1", timestampMs = 2_000L, lat = 20.0, lon = 10.0, accuracyMeters = 99f),
            ),
        )

        val p = TrackerMapLastPointResolver.resolveRenderedMarkerPoint(
            state = state,
            trackerId = "t1",
            tracker = null,
            acceptedRemoteTrackerIds = setOf("t1"),
        )

        assertNotNull(p)
        assertEquals(30.0, p!!.latitude, 0.0)
        assertEquals(40.0, p.longitude, 0.0)
        assertEquals(1_000L, p.lastUpdatedMs)
        assertEquals(12f, p.accuracyMeters)
    }

    @Test
    fun resolveRenderedMarkerPoint_allQueueUsesAcceptedRemoteOnlyWhenNoTrailMarkerExists() {
        val state = TrackerMapUiState(
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            remoteLastPoints = mapOf(
                "t1" to remotePoint("t1", timestampMs = 2_000L, lat = 20.0, lon = 10.0, accuracyMeters = 99f),
            ),
        )

        val p = TrackerMapLastPointResolver.resolveRenderedMarkerPoint(
            state = state,
            trackerId = "t1",
            tracker = null,
            acceptedRemoteTrackerIds = setOf("t1"),
        )

        assertNotNull(p)
        assertEquals(20.0, p!!.latitude, 0.0)
        assertEquals(10.0, p.longitude, 0.0)
        assertEquals(2_000L, p.lastUpdatedMs)
        assertEquals(99f, p.accuracyMeters)
    }

    @Test
    fun resolve_ignoresUnacceptedRemoteHead() {
        val state = TrackerMapUiState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "t1",
            runtime = TrackingRuntimeSnapshot(selectedTrackerId = "other"),
            trail = listOf(queued("t1", time = 1_000L, latitude = 30.0, longitude = 40.0)),
            remoteLastPoints = mapOf("t1" to remotePoint("t1", timestampMs = 2_000L, lat = 20.0, lon = 10.0)),
        )

        val p = TrackerMapLastPointResolver.resolve(state, "t1", null, acceptedRemoteTrackerIds = emptySet())

        assertNotNull(p)
        assertEquals(30.0, p!!.latitude, 0.0)
        assertEquals(40.0, p.longitude, 0.0)
        assertEquals(1_000L, p.lastUpdatedMs)
    }

    private fun queued(
        trackerId: String,
        time: Long,
        latitude: Double,
        longitude: Double,
        accuracy: Float? = null,
    ): QueuedLocation {
        return QueuedLocation(
            trackerId = trackerId,
            time = time,
            latitude = latitude,
            longitude = longitude,
            altitude = null,
            speed = null,
            bearing = null,
            accuracy = accuracy,
        )
    }

    private fun remotePoint(
        trackId: String,
        timestampMs: Long,
        lat: Double,
        lon: Double,
        accuracyMeters: Float? = null,
    ): TrackPointEvent {
        return TrackPointEvent(
            source = TrackPointSource.REMOTE_STREAM,
            trackId = trackId,
            lon = lon,
            lat = lat,
            timestampMs = timestampMs,
            accuracyMeters = accuracyMeters,
        )
    }
}
