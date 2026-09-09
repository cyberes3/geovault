package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.domain.TrackPoint
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.positioning.RecordingRuntime
import com.geovault.tracker.positioning.TrackingRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import com.geovault.tracker.map.MapSessionEngine
import com.geovault.tracker.map.MapTrailEngine
import com.geovault.tracker.map.TrailView
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
        val result = MapTrailEngine.reduceUiPoint(
            TrackerMapPointReductionInput(
                state = state,
                point = TrackPoint(
                    provenance = TrackPointSource.LOCAL_GPS,
                    trackerId = "tracker-1",
                    longitude = 10.0,
                    latitude = 20.0,
                    timeMs = 1000L,
                    accuracyMeters = 4f,
                ),
                trailPointLimit = 4000,
                sessionPlan = sessionPlanFor(state),
            )
        )
        assertTrue(result.acceptedBySourcePolicy)
        assertTrue(result.shouldUpdateUiState)
        assertEquals(1, result.nextTrails.singleTrail.size)
        assertEquals("local_gps", result.nextTrails.singleTrail.first().prov)
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
        )
        val result = MapTrailEngine.reduceUiPoint(
            TrackerMapPointReductionInput(
                state = state,
                trails = TrailView(singleTrail = listOf(existing)),
                point = TrackPoint(
                    provenance = TrackPointSource.LOCAL_GPS,
                    trackerId = "tracker-1",
                    longitude = 10.0,
                    latitude = 20.0,
                    timeMs = 1000L,
                    accuracyMeters = 4f,
                ),
                trailPointLimit = 4000,
                sessionPlan = sessionPlanFor(state),
            )
        )
        assertTrue(result.acceptedBySourcePolicy)
        assertFalse(result.shouldUpdateUiState)
        assertEquals(1, result.nextTrails.singleTrail.size)
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
        )
        val result = MapTrailEngine.reduceUiPoint(
            TrackerMapPointReductionInput(
                state = state,
                trails = TrailView(singleTrail = listOf(existing)),
                point = TrackPoint(
                    provenance = TrackPointSource.REMOTE_STREAM,
                    trackerId = "tracker-1",
                    longitude = 10.0,
                    latitude = 20.0,
                    timeMs = 1000L,
                ),
                trailPointLimit = 4000,
                sessionPlan = sessionPlanFor(state),
            )
        )
        assertTrue(result.acceptedBySourcePolicy)
        assertTrue(result.shouldUpdateUiState)
        assertEquals(2, result.nextTrails.singleTrail.size)
        assertEquals("server_geometry", result.nextTrails.singleTrail[0].prov)
        assertEquals("remote_stream", result.nextTrails.singleTrail[1].prov)
        assertTrue(result.nextTrails.remoteLastPoints.containsKey("tracker-1"))
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
        val result = MapTrailEngine.reduceUiPoint(
            TrackerMapPointReductionInput(
                state = state,
                point = TrackPoint(
                    provenance = TrackPointSource.REMOTE_STREAM,
                    trackerId = "remote",
                    longitude = 10.0,
                    latitude = 20.0,
                    timeMs = 1000L,
                ),
                trailPointLimit = 4000,
                sessionPlan = sessionPlanFor(state),
            )
        )

        assertTrue(result.acceptedBySourcePolicy)
        assertTrue(result.shouldUpdateUiState)
        assertEquals(1, result.nextTrails.singleTrail.size)
        assertEquals("remote_stream", result.nextTrails.singleTrail.first().prov)
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
        )
        val result = MapTrailEngine.reduceUiPoint(
            TrackerMapPointReductionInput(
                state = state,
                trails = TrailView(singleTrail = listOf(existing)),
                point = TrackPoint(
                    provenance = TrackPointSource.REMOTE_STREAM,
                    trackerId = "tracker-1",
                    longitude = 10.0,
                    latitude = 20.0,
                    timeMs = tsMs,
                ),
                trailPointLimit = 4000,
                sessionPlan = sessionPlanFor(state),
            )
        )
        assertTrue(result.acceptedBySourcePolicy)
        assertTrue(result.shouldUpdateUiState)
        assertEquals(1, result.nextTrails.singleTrail.size)
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
        val result = MapTrailEngine.reduceUiPoint(
            TrackerMapPointReductionInput(
                state = state,
                point = TrackPoint(
                    provenance = TrackPointSource.REMOTE_STREAM,
                    trackerId = "tracker-1",
                    longitude = 10.0,
                    latitude = 20.0,
                    timeMs = 1000L,
                ),
                trailPointLimit = 4000,
                sessionPlan = sessionPlanFor(state),
            )
        )
        assertTrue(result.acceptedBySourcePolicy)
        assertTrue(result.shouldUpdateUiState)
        assertEquals(1, result.nextTrails.tracksByTrackerId["tracker-1"]?.size)
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
        )
        val result = MapTrailEngine.reduceUiPoint(
            TrackerMapPointReductionInput(
                state = state,
                point = TrackPoint(
                    provenance = TrackPointSource.LOCAL_GPS,
                    trackerId = "tracker-1",
                    longitude = 10.0,
                    latitude = 20.0,
                    timeMs = 1000L,
                ),
                trailPointLimit = 4000,
                sessionPlan = MapSessionEngine.project(
                    TrackerMapSessionIntent(
                        mode = state.mode,
                        runtime = state.runtime,
                        selectedTrackerId = state.runtime.selectedTrackerId,
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
        assertEquals(1, result.nextTrails.tracksByTrackerId["tracker-1"]?.size)
        assertEquals(0, result.nextTrails.singleTrail.size)
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
        val result = MapTrailEngine.reduceUiPoint(
            TrackerMapPointReductionInput(
                state = state,
                point = TrackPoint(
                    provenance = TrackPointSource.LOCAL_GPS,
                    trackerId = "tracker-1",
                    longitude = 10.0,
                    latitude = 20.0,
                    timeMs = 1000L,
                ),
                trailPointLimit = 4000,
                sessionPlan = sessionPlanFor(state),
            )
        )
        assertEquals("tracker-1", result.nextTrails.singleTrail.first().trackerId)
    }

    @Test
    fun localGps_usesRecordingTrackerWhenSelectedDiffers() {
        val state = TrackerMapUiState(
            runtime = TrackingRuntimeSnapshot(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "recording"),
                selectedTrackerId = "selected",
            ),
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "recording",
        )
        val result = MapTrailEngine.reduceUiPoint(
            TrackerMapPointReductionInput(
                state = state,
                point = TrackPoint(
                    provenance = TrackPointSource.LOCAL_GPS,
                    trackerId = "recording",
                    longitude = 10.0,
                    latitude = 20.0,
                    timeMs = 1000L,
                ),
                trailPointLimit = 4000,
                sessionPlan = sessionPlanFor(state),
            )
        )

        assertTrue(result.acceptedBySourcePolicy)
        assertTrue(result.shouldUpdateUiState)
        assertEquals("recording", result.nextTrails.singleTrail.first().trackerId)
    }

    @Test
    fun localGps_rejectsSelectedTrackerWhenDifferentTrackerIsRecording() {
        val state = TrackerMapUiState(
            runtime = TrackingRuntimeSnapshot(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "recording"),
                selectedTrackerId = "selected",
            ),
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "selected",
        )
        val result = MapTrailEngine.reduceUiPoint(
            TrackerMapPointReductionInput(
                state = state,
                point = TrackPoint(
                    provenance = TrackPointSource.LOCAL_GPS,
                    trackerId = "selected",
                    longitude = 10.0,
                    latitude = 20.0,
                    timeMs = 1000L,
                ),
                trailPointLimit = 4000,
                sessionPlan = sessionPlanFor(state),
            )
        )

        assertFalse(result.acceptedBySourcePolicy)
        assertFalse(result.shouldUpdateUiState)
        assertTrue(result.nextTrails.singleTrail.isEmpty())
    }

    @Test
    fun localGps_stampsStartTimestampFromRuntime_whenPropsJsonAbsent() {
        val sessionStart = 1_700_000_000_000L
        val state = TrackerMapUiState(
            runtime = TrackingRuntimeSnapshot(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "tracker-1"),
                selectedTrackerId = "tracker-1",
                sessionStartTimeMs = sessionStart,
            ),
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
        )
        val result = MapTrailEngine.reduceUiPoint(
            TrackerMapPointReductionInput(
                state = state,
                point = TrackPoint(
                    provenance = TrackPointSource.LOCAL_GPS,
                    trackerId = "tracker-1",
                    longitude = 1.0,
                    latitude = 2.0,
                    timeMs = sessionStart + 5_000L,
                    propsJson = null,
                ),
                trailPointLimit = 4000,
                sessionPlan = sessionPlanFor(state),
            )
        )
        assertEquals(sessionStart, result.nextTrails.singleTrail.first().startTimestampMs)
    }

    @Test
    fun localGps_prefersPropsJsonStartTimestampOverRuntime() {
        val state = TrackerMapUiState(
            runtime = TrackingRuntimeSnapshot(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "tracker-1"),
                selectedTrackerId = "tracker-1",
                sessionStartTimeMs = 5_000L,
            ),
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
        )
        val result = MapTrailEngine.reduceUiPoint(
            TrackerMapPointReductionInput(
                state = state,
                point = TrackPoint(
                    provenance = TrackPointSource.LOCAL_GPS,
                    trackerId = "tracker-1",
                    longitude = 1.0,
                    latitude = 2.0,
                    timeMs = 6_000L,
                    propsJson = """{"starttimestamp": 9000000000000}""",
                ),
                trailPointLimit = 4000,
                sessionPlan = sessionPlanFor(state),
            )
        )
        assertEquals(9_000_000_000_000L, result.nextTrails.singleTrail.first().startTimestampMs)
    }

    @Test
    fun remoteStream_stampsStartTimestampFromPropsJson() {
        val state = TrackerMapUiState(
            runtime = TrackingRuntimeSnapshot(
                isRunning = false,
                selectedTrackerId = "tracker-1",
            ),
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "tracker-1",
        )
        val result = MapTrailEngine.reduceUiPoint(
            TrackerMapPointReductionInput(
                state = state,
                point = TrackPoint(
                    provenance = TrackPointSource.REMOTE_STREAM,
                    trackerId = "tracker-1",
                    longitude = 10.0,
                    latitude = 20.0,
                    timeMs = 1_710_000_000_000L,
                    propsJson = """{"starttimestamp": 1700000000000}""",
                ),
                trailPointLimit = 4000,
                sessionPlan = sessionPlanFor(state),
            )
        )
        assertEquals(1_700_000_000_000L, result.nextTrails.singleTrail.first().startTimestampMs)
    }

    @Test
    fun localGps_newSessionFirstFix_isNotRejectedAsDuplicateWhenTimeMatchesPriorSessionTail() {
        // SESSION-AWARE DUPLICATE GUARD: a new session's first fix can collide on `time`
        // (and even lat/lon, if the device hasn't moved) with the previous session's tail.
        // The original time-only duplicate guard silently dropped that point and the new
        // session never received a head, leaving the chevron pinned to the prior session.
        val priorSession = 1_000L
        val newSession = 2_000L
        val priorTail = QueuedLocation(
            id = 0L,
            trackerId = "tracker-1",
            time = 5_000L,
            latitude = 20.0,
            longitude = 10.0,
            altitude = null,
            speed = null,
            bearing = null,
            accuracy = null,
            sat = null,
            prov = MapTrailEngine.PROVENANCE_LOCAL_GPS,
            dist = null,
            startTimestampMs = priorSession,
        )
        val state = TrackerMapUiState(
            runtime = TrackingRuntimeSnapshot(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "tracker-1"),
                selectedTrackerId = "tracker-1",
                sessionStartTimeMs = newSession,
            ),
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
        )

        val result = MapTrailEngine.reduceUiPoint(
            TrackerMapPointReductionInput(
                state = state,
                trails = TrailView(singleTrail = listOf(priorTail)),
                point = TrackPoint(
                    provenance = TrackPointSource.LOCAL_GPS,
                    trackerId = "tracker-1",
                    longitude = 10.0,
                    latitude = 20.0,
                    timeMs = 5_000L,
                ),
                trailPointLimit = 4000,
                sessionPlan = sessionPlanFor(state),
            )
        )

        assertTrue(result.shouldUpdateUiState)
        assertEquals(2, result.nextTrails.singleTrail.size)
        assertEquals(priorSession, result.nextTrails.singleTrail.first().startTimestampMs)
        assertEquals(newSession, result.nextTrails.singleTrail.last().startTimestampMs)
    }

    private fun sessionPlanFor(state: TrackerMapUiState): TrackerMapStreamingPlan {
        val visibleIds = buildSet {
            state.runtime.selectedTrackerId.trim().takeIf { it.isNotEmpty() }?.let(::add)
            state.displayedTrackerId.trim().takeIf { it.isNotEmpty() }?.let(::add)
            addAll(state.activeStreamedTrackerIds)
        }
        return MapSessionEngine.project(
            TrackerMapSessionIntent(
                mode = state.mode,
                runtime = state.runtime,
                selectedTrackerId = state.runtime.selectedTrackerId,
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
