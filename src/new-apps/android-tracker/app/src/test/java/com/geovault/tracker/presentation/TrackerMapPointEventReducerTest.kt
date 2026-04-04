package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapPointEventReducerTest {

    @Test
    fun localGpsTrackingSingle_appendsLocalOverlayPoint() {
        val state = TrackerMapUiState(
            runtime = TrackingRuntimeSnapshot(
                isRunning = true,
                selectedTrackerId = "tracker-1",
            ),
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
        )
        val result = TrackerMapPointEventReducer.reduce(
            TrackerMapPointReductionInput(
                state = state,
                point = TrackPointEvent(
                    source = TrackPointSource.LOCAL_GPS,
                    trackId = "tracker-1",
                    lon = 10.0,
                    lat = 20.0,
                    timestampMs = 1000L,
                    accuracyMeters = 4f,
                ),
                recentDataWindow = null,
                currentSessionStartMs = null,
                pendingReopenTrackerId = null,
                sessionAnchorTrackerId = null,
                sessionAnchorUntilElapsedMs = 0L,
                nowElapsedMs = 0L,
                trailPointLimit = 4000,
            )
        )
        assertTrue(result.acceptedBySourcePolicy)
        assertTrue(result.shouldUpdateUiState)
        assertEquals(1, result.nextState.trail.size)
        assertEquals("local_gps", result.nextState.trail.first().prov)
    }

    @Test
    fun localGpsDuplicateTail_doesNotMutateUiState() {
        val existing = QueuedLocation(
            id = 0L,
            time = 1000L,
            latitude = 20.0,
            longitude = 10.0,
            altitude = null,
            speed = null,
            bearing = null,
            accuracy = 5f,
            sat = null,
            prov = "local_gps",
            dist = null
        )
        val state = TrackerMapUiState(
            runtime = TrackingRuntimeSnapshot(
                isRunning = true,
                selectedTrackerId = "tracker-1",
            ),
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            trail = listOf(existing),
        )
        val result = TrackerMapPointEventReducer.reduce(
            TrackerMapPointReductionInput(
                state = state,
                point = TrackPointEvent(
                    source = TrackPointSource.LOCAL_GPS,
                    trackId = "tracker-1",
                    lon = 10.0,
                    lat = 20.0,
                    timestampMs = 1000L,
                    accuracyMeters = 4f,
                ),
                recentDataWindow = null,
                currentSessionStartMs = null,
                pendingReopenTrackerId = null,
                sessionAnchorTrackerId = null,
                sessionAnchorUntilElapsedMs = 0L,
                nowElapsedMs = 0L,
                trailPointLimit = 4000,
            )
        )
        assertTrue(result.acceptedBySourcePolicy)
        assertFalse(result.shouldUpdateUiState)
        assertEquals(1, result.nextState.trail.size)
    }

    @Test
    fun remotePointSessionWindowNewer_resetsTrailAndAdvancesSessionAnchor() {
        val state = TrackerMapUiState(
            runtime = TrackingRuntimeSnapshot(
                isRunning = false,
                selectedTrackerId = "tracker-1",
            ),
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "tracker-1",
            trail = listOf(
                QueuedLocation(
                    id = 0L,
                    time = 900L,
                    latitude = 0.0,
                    longitude = 0.0,
                    altitude = null,
                    speed = null,
                    bearing = null,
                    accuracy = null,
                    sat = null,
                    prov = "remote_stream",
                    dist = null
                )
            ),
        )
        val result = TrackerMapPointEventReducer.reduce(
            TrackerMapPointReductionInput(
                state = state,
                point = TrackPointEvent(
                    source = TrackPointSource.REMOTE_STREAM,
                    trackId = "tracker-1",
                    lon = 10.0,
                    lat = 20.0,
                    timestampMs = 1000L,
                    propsJson = "{\"starttimestamp\":2000}"
                ),
                recentDataWindow = "session",
                currentSessionStartMs = 1000L,
                pendingReopenTrackerId = null,
                sessionAnchorTrackerId = null,
                sessionAnchorUntilElapsedMs = 0L,
                nowElapsedMs = 0L,
                trailPointLimit = 4000,
            )
        )
        assertTrue(result.acceptedBySourcePolicy)
        assertTrue(result.shouldUpdateUiState)
        assertEquals(2_000_000L, result.nextSessionStartMs)
        assertEquals(1, result.nextState.trail.size)
        assertTrue(result.nextState.remoteLastPoints.containsKey("tracker-1"))
    }

    @Test
    fun remotePointStaleSession_ignoredWithoutUiMutation() {
        val state = TrackerMapUiState(
            runtime = TrackingRuntimeSnapshot(isRunning = false, selectedTrackerId = "tracker-1"),
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "tracker-1",
        )
        val result = TrackerMapPointEventReducer.reduce(
            TrackerMapPointReductionInput(
                state = state,
                point = TrackPointEvent(
                    source = TrackPointSource.REMOTE_STREAM,
                    trackId = "tracker-1",
                    lon = 10.0,
                    lat = 20.0,
                    timestampMs = 1000L,
                    propsJson = "{\"starttimestamp\":1000}"
                ),
                recentDataWindow = "session",
                currentSessionStartMs = 2_000_000L,
                pendingReopenTrackerId = null,
                sessionAnchorTrackerId = null,
                sessionAnchorUntilElapsedMs = 0L,
                nowElapsedMs = 0L,
                trailPointLimit = 4000,
            )
        )
        assertTrue(result.acceptedBySourcePolicy)
        assertFalse(result.shouldUpdateUiState)
        assertEquals(2_000_000L, result.nextSessionStartMs)
    }
}
