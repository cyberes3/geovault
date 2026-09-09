package com.geovault.tracker.map

import com.geovault.tracker.Tracker
import com.geovault.tracker.domain.TrackPoint
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.positioning.TrackingRuntimeSnapshot
import com.geovault.tracker.presentation.TrackerMapDisplayMode
import com.geovault.tracker.presentation.TrackerMapSessionSnapshot
import com.geovault.tracker.presentation.TrackerMapStreamingPlan
import com.geovault.tracker.presentation.TrackerMapTrailReloadPlan
import com.geovault.tracker.presentation.TrackerMapTrailSource
import com.geovault.tracker.presentation.TrackerMapUiState
import com.geovault.tracker.presentation.TrackerTrackModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MapRenderLastPointTest {

    @Test
    fun resolve_looksUpLiveHeadOnly() {
        val head = remotePoint("t1", timestampMs = 2_000L, lat = 21.0, lon = 11.0, accuracyMeters = 4f)
        val p = MapRenderMath.resolveLastPoint("t1", mapOf("t1" to head))
        assertNotNull(p)
        assertEquals(21.0, p!!.latitude, 0.0)
        assertEquals(11.0, p.longitude, 0.0)
        assertEquals(2_000L, p.lastUpdatedMs)
        assertEquals(4f, p.accuracyMeters)
    }

    @Test
    fun resolve_emptyTrackerId_returnsNull() {
        val head = remotePoint("t1", timestampMs = 2_000L, lat = 21.0, lon = 11.0)
        assertNull(MapRenderMath.resolveLastPoint("  ", mapOf("t1" to head)))
    }

    @Test
    fun resolve_missingLiveHead_returnsNull() {
        val roster = Tracker(
            id = "t1",
            name = "T1",
            color = null,
            last_point = listOf(-10.0, 20.0, 0.0),
            updated_at = 1_800_000_000_000L,
        )
        val state = TrackerMapUiState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "t1",
        )
        assertNull(
            MapRenderMath.resolveLastPoint(
                state = state,
                trackerId = "t1",
                tracker = roster,
                acceptedRemoteTrackerIds = emptySet(),
            )
        )
    }

    @Test
    fun resolve_ignoresRosterAndRuntimeWhenLiveHeadPresent() {
        val state = TrackerMapUiState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "t1",
            runtime = TrackingRuntimeSnapshot(
                lastTrackedLatitude = 30.0,
                lastTrackedLongitude = 40.0,
                lastTrackedTimestampMs = 9_000L,
            ),
        )
        val roster = Tracker(
            id = "t1",
            name = "T1",
            color = null,
            last_point = listOf(-10.0, 20.0, 0.0),
            updated_at = 1_000L,
        )
        val head = remotePoint("t1", timestampMs = 2_000L, lat = 5.0, lon = 6.0)
        val p = MapRenderMath.resolveLastPoint(
            state = state,
            trackerId = "t1",
            tracker = roster,
            acceptedRemoteTrackerIds = setOf("t1"),
            remoteLastPoints = mapOf("t1" to head),
        )
        assertNotNull(p)
        assertEquals(5.0, p!!.latitude, 0.0)
        assertEquals(6.0, p.longitude, 0.0)
        assertEquals(2_000L, p.lastUpdatedMs)
    }

    @Test
    fun resolve_snapshot_usesAcceptedRemoteLastPointsAsLiveHeads() {
        val remote = remotePoint("t1", timestampMs = 2_000L, lat = 21.0, lon = 11.0)
        val snapshot = TrackerMapSessionSnapshot(
            uiState = TrackerMapUiState(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                displayedTrackerId = "t1",
                selectionLockTrackerId = "t1",
            ),
            plan = TrackerMapStreamingPlan(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                selectedTrackerId = "other",
                displayedTrackerId = "t1",
                displayedTrackerName = "T1",
                resolvedGroupId = "",
                groupTrackerIds = emptySet(),
                visibleRosterTrackerIds = setOf("t1"),
                locallyRecordedTrackerIds = emptySet(),
                remoteSubscriptionIds = setOf("t1"),
                acceptedRemoteTrackerIds = setOf("t1"),
                localOverlayTrackerIds = emptySet(),
                trailReloadPlan = TrackerMapTrailReloadPlan(source = TrackerMapTrailSource.SINGLE_SERVER),
            ),
            runtime = TrackingRuntimeSnapshot(selectedTrackerId = "other"),
            singleTrail = emptyList(),
            tracks = mapOf("t1" to TrackerTrackModel(trackerId = "t1", renderTrail = emptyList(), remoteHead = remote)),
            acceptedRemoteLastPoints = mapOf("t1" to remote),
        )

        val p = MapRenderMath.resolveLastPoint(snapshot, "t1", null)
        assertNotNull(p)
        assertEquals(21.0, p!!.latitude, 0.0)
        assertEquals(11.0, p.longitude, 0.0)
        assertEquals(2_000L, p.lastUpdatedMs)
    }

    @Test
    fun resolve_snapshot_missingHead_returnsNull() {
        val snapshot = TrackerMapSessionSnapshot(
            uiState = TrackerMapUiState(mode = TrackerMapDisplayMode.SINGLE_SESSION, displayedTrackerId = "t1"),
            plan = TrackerMapStreamingPlan(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                selectedTrackerId = "other",
                displayedTrackerId = "t1",
                displayedTrackerName = "T1",
                resolvedGroupId = "",
                groupTrackerIds = emptySet(),
                visibleRosterTrackerIds = setOf("t1"),
                locallyRecordedTrackerIds = emptySet(),
                remoteSubscriptionIds = emptySet(),
                acceptedRemoteTrackerIds = emptySet(),
                localOverlayTrackerIds = emptySet(),
                trailReloadPlan = TrackerMapTrailReloadPlan(source = TrackerMapTrailSource.SINGLE_SERVER),
            ),
            runtime = TrackingRuntimeSnapshot(),
            singleTrail = emptyList(),
            tracks = emptyMap(),
            acceptedRemoteLastPoints = emptyMap(),
        )
        assertNull(MapRenderMath.resolveLastPoint(snapshot, "t1", null))
    }

    private fun remotePoint(
        trackId: String,
        timestampMs: Long,
        lat: Double,
        lon: Double,
        accuracyMeters: Float? = null,
    ): TrackPoint {
        return TrackPoint(
            provenance = TrackPointSource.REMOTE_STREAM,
            trackerId = trackId,
            longitude = lon,
            latitude = lat,
            timeMs = timestampMs,
            accuracyMeters = accuracyMeters,
        )
    }
}
