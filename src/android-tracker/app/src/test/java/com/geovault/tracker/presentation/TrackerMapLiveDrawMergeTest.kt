package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.map.MapTrailEngine
import com.geovault.tracker.domain.TrackPoint
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.positioning.TrackingRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapLiveDrawMergeTest {
    @Test
    fun mergeSingle_emptySnapshotDeferred_paintsUnpublishedOverlayAndMarkerAtOverlay() {
        val overlay = listOf(queued("t1", time = 2_000L, latitude = 5.0, longitude = 6.0))
        val merged = MapTrailEngine.mergeLiveDrawSingle(
            mappedTrail = emptyList(),
            unpublishedOverlay = overlay,
            remoteLastPoint = null,
            runtime = TrackingRuntimeSnapshot(selectedTrackerId = "other"),
            displayedTrackerId = "t1",
            trailPointLimit = 10_000,
        )

        assertEquals(1, merged.size)
        assertEquals(5.0, merged.last().latitude, 0.0)
        assertEquals(6.0, merged.last().longitude, 0.0)
        assertEquals(2_000L, merged.last().time)
    }

    @Test
    fun mergeSingle_olderTrunkPlusNewerRemoteHead_movesMarkerToRemote() {
        val trunk = listOf(queued("t1", time = 1_000L, latitude = 1.0, longitude = 2.0))
        val remote = TrackPoint(
            provenance = TrackPointSource.REMOTE_STREAM,
            trackerId = "t1",
            longitude = 8.0,
            latitude = 7.0,
            timeMs = 3_000L,
        )
        val merged = MapTrailEngine.mergeLiveDrawSingle(
            mappedTrail = trunk,
            unpublishedOverlay = emptyList(),
            remoteLastPoint = remote,
            runtime = TrackingRuntimeSnapshot(selectedTrackerId = "other"),
            displayedTrackerId = "t1",
            trailPointLimit = 10_000,
        )

        assertEquals(2, merged.size)
        assertEquals(7.0, merged.last().latitude, 0.0)
        assertEquals(8.0, merged.last().longitude, 0.0)
    }

    @Test
    fun mergeMulti_oneMemberRemoteHeadNewerThanTrail_movesThatMember() {
        val merged = MapTrailEngine.mergeLiveDrawMulti(
            mappedTrails = mapOf(
                "a" to listOf(queued("a", time = 1_000L, latitude = 1.0, longitude = 2.0)),
                "b" to listOf(queued("b", time = 1_000L, latitude = 3.0, longitude = 4.0)),
            ),
            unpublishedOverlaysByTracker = emptyMap(),
            remoteLastPoints = mapOf(
                "b" to TrackPoint(
                    provenance = TrackPointSource.REMOTE_STREAM,
                    trackerId = "b",
                    longitude = 8.0,
                    latitude = 7.0,
                    timeMs = 4_000L,
                ),
            ),
            runtime = TrackingRuntimeSnapshot(),
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            groupTrackerIds = emptySet(),
            trailPointLimit = 10_000,
        )

        assertEquals(1.0, merged.getValue("a").last().latitude, 0.0)
        assertEquals(7.0, merged.getValue("b").last().latitude, 0.0)
        assertEquals(8.0, merged.getValue("b").last().longitude, 0.0)
        assertTrue(merged.getValue("b").size >= 2)
    }

    private fun queued(
        trackerId: String,
        time: Long,
        latitude: Double,
        longitude: Double,
    ): QueuedLocation {
        return QueuedLocation(
            trackerId = trackerId,
            time = time,
            latitude = latitude,
            longitude = longitude,
            altitude = null,
            speed = null,
            bearing = null,
            accuracy = null,
        )
    }
}
