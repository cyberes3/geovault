package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.services.RecordingRuntime
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapSessionEngineTest {

    @Test
    fun build_splitsHistoricalAndLiveTrails() {
        val snapshot = TrackerMapSessionEngine.build(
            TrackerMapSessionBuildInput(
                state = TrackerMapUiState(
                    mode = TrackerMapDisplayMode.ALL_QUEUE,
                    runtime = TrackingRuntimeSnapshot(
                        recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "local"),
                        selectedTrackerId = "local",
                    ),
                    allQueueTrailsByTracker = mapOf(
                        "local" to listOf(
                            queued("local", id = 1L, time = 10L, prov = "server_geometry"),
                            queued("local", id = 0L, time = 20L, prov = "local_gps"),
                        )
                    ),
                ),
                plan = plan(),
                localRuntimeOverlayTrails = mapOf(
                    "local" to listOf(
                        queued("local", id = 1L, time = 10L, prov = "server_geometry"),
                        queued("local", id = 0L, time = 20L, prov = "local_gps"),
                    )
                ),
            )
        )

        val track = snapshot.tracks.getValue("local")
        assertEquals(1, track.historicalTrail.size)
        assertEquals(1, track.liveTrail.size)
        assertEquals(listOf(10L, 20L), track.renderTrail.map { it.time })
    }

    @Test
    fun build_keepsNegativeIdServerGeometryHistorical() {
        val snapshot = TrackerMapSessionEngine.build(
            TrackerMapSessionBuildInput(
                state = TrackerMapUiState(mode = TrackerMapDisplayMode.ALL_QUEUE),
                plan = plan(),
                localRuntimeOverlayTrails = mapOf(
                    "remote" to listOf(
                        queued("remote", id = -1L, time = 10L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY),
                        queued("remote", id = 0L, time = 20L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_REMOTE_STREAM),
                    )
                ),
            )
        )

        val track = snapshot.tracks.getValue("remote")
        assertEquals(listOf(-1L), track.historicalTrail.map { it.id })
        assertEquals(listOf(0L), track.liveTrail.map { it.id })
    }

    @Test
    fun reducePoint_remoteAccepted_updatesSnapshotState() {
        val initial = TrackerMapSessionEngine.build(
            TrackerMapSessionBuildInput(
                state = TrackerMapUiState(
                    mode = TrackerMapDisplayMode.SINGLE_SESSION,
                    displayedTrackerId = "remote",
                    streamTargetIds = setOf("remote"),
                    activeStreamedTrackerIds = setOf("remote"),
                ),
                plan = plan(
                    mode = TrackerMapDisplayMode.SINGLE_SESSION,
                    displayedTrackerId = "remote",
                    acceptedRemoteTrackerIds = setOf("remote"),
                    remoteSubscriptionIds = setOf("remote"),
                ),
            )
        )

        val result = TrackerMapSessionEngine.reducePoint(
            TrackerMapSessionPointInput(
                snapshot = initial,
                point = TrackPointEvent(
                    source = TrackPointSource.REMOTE_STREAM,
                    trackId = "remote",
                    lon = 2.0,
                    lat = 1.0,
                    timestampMs = 100L,
                ),
                trailPointLimit = 100,
            )
        )

        assertTrue(result.shouldUpdate)
        assertEquals("remote", result.nextSnapshot.uiState.trail.single().trackerId)
        assertEquals("remote", result.nextSnapshot.acceptedRemoteLastPoints.keys.single())
    }

    @Test
    fun reducePoint_preservesRuntimeOverlayTracksFromInputSnapshot() {
        val initial = TrackerMapSessionEngine.build(
            TrackerMapSessionBuildInput(
                state = TrackerMapUiState(mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER),
                plan = plan(
                    mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
                    acceptedRemoteTrackerIds = setOf("remote"),
                    remoteSubscriptionIds = setOf("remote"),
                ),
                localRuntimeOverlayTrails = mapOf(
                    "local" to listOf(
                        queued(
                            trackerId = "local",
                            id = 0L,
                            time = 10L,
                            prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS_RUNTIME,
                        )
                    )
                ),
            )
        )

        val result = TrackerMapSessionEngine.reducePoint(
            TrackerMapSessionPointInput(
                snapshot = initial,
                point = TrackPointEvent(
                    source = TrackPointSource.REMOTE_STREAM,
                    trackId = "remote",
                    lon = 2.0,
                    lat = 1.0,
                    timestampMs = 100L,
                ),
                trailPointLimit = 100,
            )
        )

        assertTrue(result.shouldUpdate)
        assertEquals(setOf("local", "remote"), result.nextSnapshot.tracks.keys)
        assertEquals(
            listOf(TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS_RUNTIME),
            result.nextSnapshot.tracks.getValue("local").renderTrail.map { it.prov },
        )
    }

    @Test
    fun build_currentSession_filtersOlderSessionsFromMultiTrail() {
        val s1 = 1_000L
        val s2 = 2_000L
        val snapshot = TrackerMapSessionEngine.build(
            TrackerMapSessionBuildInput(
                state = TrackerMapUiState(
                    mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
                ),
                plan = plan(mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER),
                localRuntimeOverlayTrails = mapOf(
                    "tracker-a" to listOf(
                        queued("tracker-a", id = -1L, time = 10L, prov = "server_geometry", startTimestampMs = s1),
                        queued("tracker-a", id = -2L, time = 20L, prov = "server_geometry", startTimestampMs = s1),
                        queued("tracker-a", id = 0L, time = 30L, prov = "local_gps", startTimestampMs = s2),
                    )
                ),
                recentDataWindowByTracker = mapOf("tracker-a" to "current_session"),
                nowMs = 1_000_000L,
            )
        )
        val track = snapshot.tracks.getValue("tracker-a")
        assertEquals(listOf(30L), track.renderTrail.map { it.time })
    }

    @Test
    fun build_appliesPerTrackerWindowIndependently() {
        val s1 = 1_000L
        val s2 = 2_000L
        val snapshot = TrackerMapSessionEngine.build(
            TrackerMapSessionBuildInput(
                state = TrackerMapUiState(mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER),
                plan = plan(mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER),
                localRuntimeOverlayTrails = mapOf(
                    "tracker-a" to listOf(
                        queued("tracker-a", id = -1L, time = 10L, prov = "server_geometry", startTimestampMs = s1),
                        queued("tracker-a", id = 0L, time = 20L, prov = "local_gps", startTimestampMs = s2),
                    ),
                    "tracker-b" to listOf(
                        queued("tracker-b", id = -1L, time = 10L, prov = "server_geometry", startTimestampMs = s1),
                        queued("tracker-b", id = 0L, time = 20L, prov = "local_gps", startTimestampMs = s2),
                    ),
                ),
                recentDataWindowByTracker = mapOf(
                    "tracker-a" to "current_session",
                    "tracker-b" to "all",
                ),
                nowMs = 1_000_000L,
            )
        )
        assertEquals(listOf(20L), snapshot.tracks.getValue("tracker-a").renderTrail.map { it.time })
        assertEquals(listOf(10L, 20L), snapshot.tracks.getValue("tracker-b").renderTrail.map { it.time })
    }

    @Test
    fun build_singleTrail_appliesActiveTrackerWindow() {
        val s1 = 1_000L
        val s2 = 2_000L
        val snapshot = TrackerMapSessionEngine.build(
            TrackerMapSessionBuildInput(
                state = TrackerMapUiState(
                    mode = TrackerMapDisplayMode.SINGLE_SESSION,
                    displayedTrackerId = "active",
                    trail = listOf(
                        queued("active", id = -1L, time = 10L, prov = "server_geometry", startTimestampMs = s1),
                        queued("active", id = 0L, time = 30L, prov = "local_gps", startTimestampMs = s2),
                    ),
                ),
                plan = plan(
                    mode = TrackerMapDisplayMode.SINGLE_SESSION,
                    displayedTrackerId = "active",
                ),
                localRuntimeOverlayTrails = emptyMap(),
                recentDataWindowByTracker = mapOf("active" to "current_session"),
                nowMs = 1_000_000L,
            )
        )
        assertEquals(listOf(30L), snapshot.singleTrail.map { it.time })
    }

    @Test
    fun build_singleTrailWithRestoredLocalTrail_keepsPreviousAndCurrentSessions() {
        val older = 1_000L
        val previous = 2_000L
        val current = 3_000L
        val restoredTrail = listOf(
            queued("active", id = 1L, time = 10L, prov = "local_gps", startTimestampMs = older),
            queued("active", id = 2L, time = 20L, prov = "local_gps", startTimestampMs = older),
            queued("active", id = 3L, time = 30L, prov = "local_gps", startTimestampMs = previous),
            queued("active", id = 4L, time = 40L, prov = "local_gps", startTimestampMs = previous),
            queued("active", id = 0L, time = 50L, prov = "local_gps_runtime", startTimestampMs = current),
        )

        val snapshot = TrackerMapSessionEngine.build(
            TrackerMapSessionBuildInput(
                state = TrackerMapUiState(
                    mode = TrackerMapDisplayMode.SINGLE_SESSION,
                    displayedTrackerId = "active",
                    runtime = TrackingRuntimeSnapshot(
                        isRunning = true,
                        recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "active"),
                        selectedTrackerId = "active",
                        sessionStartTimeMs = current,
                    ),
                    trail = restoredTrail,
                ),
                plan = plan(
                    mode = TrackerMapDisplayMode.SINGLE_SESSION,
                    displayedTrackerId = "active",
                ),
                recentDataWindowByTracker = mapOf("active" to "session"),
                currentSessionStartByTracker = mapOf("active" to current),
                nowMs = 1_000_000L,
            )
        )

        assertEquals(listOf(30L, 40L, 50L), snapshot.singleTrail.map { it.time })
    }

    private fun plan(
        mode: TrackerMapDisplayMode = TrackerMapDisplayMode.ALL_QUEUE,
        displayedTrackerId: String = "",
        acceptedRemoteTrackerIds: Set<String> = emptySet(),
        remoteSubscriptionIds: Set<String> = emptySet(),
    ): TrackerMapStreamingPlan {
        return TrackerMapStreamingPlan(
            mode = mode,
            selectedTrackerId = "local",
            displayedTrackerId = displayedTrackerId,
            displayedTrackerName = "",
            resolvedGroupId = "",
            groupTrackerIds = emptySet(),
            visibleRosterTrackerIds = setOf("local"),
            locallyRecordedTrackerIds = setOf("local"),
            remoteSubscriptionIds = remoteSubscriptionIds,
            acceptedRemoteTrackerIds = acceptedRemoteTrackerIds,
            localOverlayTrackerIds = setOf("local"),
            trailReloadPlan = TrackerMapTrailReloadPlan(source = TrackerMapTrailSource.MULTI_SERVER),
        )
    }

    private fun queued(
        trackerId: String,
        id: Long,
        time: Long,
        prov: String,
        startTimestampMs: Long? = null,
    ): QueuedLocation {
        return QueuedLocation(
            id = id,
            trackerId = trackerId,
            time = time,
            latitude = time.toDouble(),
            longitude = time.toDouble(),
            altitude = null,
            speed = null,
            bearing = null,
            accuracy = null,
            sat = null,
            prov = prov,
            dist = null,
            startTimestampMs = startTimestampMs,
        )
    }
}
