package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.services.RecordingRuntime
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
                recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "tracker-1"),
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
                trailPointLimit = 4000,
                sessionPlan = sessionPlanFor(state),
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
            trackerId = "tracker-1",
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
                recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "tracker-1"),
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
                trailPointLimit = 4000,
                sessionPlan = sessionPlanFor(state),
            )
        )
        assertTrue(result.acceptedBySourcePolicy)
        assertFalse(result.shouldUpdateUiState)
        assertEquals(1, result.nextState.trail.size)
    }

    @Test
    fun remoteStream_appendsToExistingTrail() {
        val existing = QueuedLocation(
            id = 0L,
            trackerId = "tracker-1",
            time = 900L,
            latitude = 0.0,
            longitude = 0.0,
            altitude = null,
            speed = null,
            bearing = null,
            accuracy = null,
            sat = null,
            prov = "server_geometry",
            dist = null
        )
        val state = TrackerMapUiState(
            runtime = TrackingRuntimeSnapshot(
                isRunning = false,
                selectedTrackerId = "tracker-1",
            ),
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "tracker-1",
            trail = listOf(existing),
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
                ),
                trailPointLimit = 4000,
                sessionPlan = sessionPlanFor(state),
            )
        )
        assertTrue(result.acceptedBySourcePolicy)
        assertTrue(result.shouldUpdateUiState)
        assertEquals(2, result.nextState.trail.size)
        assertEquals("server_geometry", result.nextState.trail[0].prov)
        assertEquals("remote_stream", result.nextState.trail[1].prov)
        assertTrue(result.nextState.remoteLastPoints.containsKey("tracker-1"))
    }

    @Test
    fun remoteStream_singleRemoteWhileTracking_appendsDisplayedTrail() {
        val state = TrackerMapUiState(
            runtime = TrackingRuntimeSnapshot(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "local"),
                selectedTrackerId = "local",
            ),
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "remote",
        )
        val result = TrackerMapPointEventReducer.reduce(
            TrackerMapPointReductionInput(
                state = state,
                point = TrackPointEvent(
                    source = TrackPointSource.REMOTE_STREAM,
                    trackId = "remote",
                    lon = 10.0,
                    lat = 20.0,
                    timestampMs = 1000L,
                ),
                trailPointLimit = 4000,
                sessionPlan = sessionPlanFor(state),
            )
        )

        assertTrue(result.acceptedBySourcePolicy)
        assertTrue(result.shouldUpdateUiState)
        assertEquals(1, result.nextState.trail.size)
        assertEquals("remote_stream", result.nextState.trail.first().prov)
    }

    @Test
    fun remoteStream_rejectsDuplicatePoint() {
        val tsMs = 1_710_000_000_000L
        val existing = QueuedLocation(
            id = 0L,
            trackerId = "tracker-1",
            time = tsMs,
            latitude = 20.0,
            longitude = 10.0,
            altitude = null,
            speed = null,
            bearing = null,
            accuracy = null,
            sat = null,
            prov = "remote_stream",
            dist = null
        )
        val state = TrackerMapUiState(
            runtime = TrackingRuntimeSnapshot(
                isRunning = false,
                selectedTrackerId = "tracker-1",
            ),
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "tracker-1",
            trail = listOf(existing),
        )
        val result = TrackerMapPointEventReducer.reduce(
            TrackerMapPointReductionInput(
                state = state,
                point = TrackPointEvent(
                    source = TrackPointSource.REMOTE_STREAM,
                    trackId = "tracker-1",
                    lon = 10.0,
                    lat = 20.0,
                    timestampMs = tsMs,
                ),
                trailPointLimit = 4000,
                sessionPlan = sessionPlanFor(state),
            )
        )
        assertTrue(result.acceptedBySourcePolicy)
        assertTrue(result.shouldUpdateUiState)
        assertEquals(1, result.nextState.trail.size)
    }

    @Test
    fun remoteStream_multiMode_appendsToTrackerMap() {
        val state = TrackerMapUiState(
            runtime = TrackingRuntimeSnapshot(
                isRunning = false,
                selectedTrackerId = "",
            ),
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            activeStreamedTrackerIds = setOf("tracker-1"),
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
                ),
                trailPointLimit = 4000,
                sessionPlan = sessionPlanFor(state),
            )
        )
        assertTrue(result.acceptedBySourcePolicy)
        assertTrue(result.shouldUpdateUiState)
        assertEquals(1, result.nextState.allQueueTrailsByTracker["tracker-1"]?.size)
    }

    @Test
    fun localGps_multiMode_appendsSelectedTrackerTrailMap() {
        val state = TrackerMapUiState(
            runtime = TrackingRuntimeSnapshot(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "tracker-1"),
                selectedTrackerId = "tracker-1",
            ),
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            activeStreamedTrackerIds = setOf("tracker-2"),
            streamTargetIds = setOf("tracker-2"),
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
                ),
                trailPointLimit = 4000,
                sessionPlan = TrackerMapSessionProjector.project(
                    TrackerMapSessionIntent(
                        mode = state.mode,
                        runtime = state.runtime,
                        displayedTrackerId = state.displayedTrackerId,
                        displayedTrackerName = state.displayedTrackerName,
                        rosterTrackerIds = emptySet(),
                        groupSelection = TrackerMapGroupModeSelection(groupId = "g1", trackerIds = setOf("tracker-1", "tracker-2")),
                        activeStreamedTrackerIds = state.activeStreamedTrackerIds,
                    )
                ),
            )
        )
        assertTrue(result.acceptedBySourcePolicy)
        assertTrue(result.shouldUpdateUiState)
        assertEquals(1, result.nextState.allQueueTrailsByTracker["tracker-1"]?.size)
        assertEquals(0, result.nextState.trail.size)
    }

    @Test
    fun localGps_usesEventTrackerIdForOverlay() {
        val state = TrackerMapUiState(
            runtime = TrackingRuntimeSnapshot(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "tracker-1"),
                selectedTrackerId = "tracker-1",
            ),
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "tracker-1",
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
                ),
                trailPointLimit = 4000,
                sessionPlan = sessionPlanFor(state),
            )
        )
        assertEquals("tracker-1", result.nextState.trail.first().trackerId)
    }

    private fun sessionPlanFor(state: TrackerMapUiState): TrackerMapStreamingPlan {
        val visibleIds = buildSet {
            state.runtime.selectedTrackerId.trim().takeIf { it.isNotEmpty() }?.let(::add)
            state.displayedTrackerId.trim().takeIf { it.isNotEmpty() }?.let(::add)
            addAll(state.streamTargetIds)
            addAll(state.activeStreamedTrackerIds)
            addAll(state.remoteLastPoints.keys)
            addAll(state.allQueueTrailsByTracker.keys)
        }
        return TrackerMapSessionProjector.project(
            TrackerMapSessionIntent(
                mode = state.mode,
                runtime = state.runtime,
                displayedTrackerId = state.displayedTrackerId,
                displayedTrackerName = state.displayedTrackerName,
                rosterTrackerIds = visibleIds,
                groupSelection = TrackerMapGroupModeSelection(
                    groupId = state.currentGroupId.ifBlank { null },
                    trackerIds = if (state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) visibleIds else emptySet(),
                ),
                activeStreamedTrackerIds = state.activeStreamedTrackerIds,
            )
        )
    }
}
