package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TrackerMapTrailMergePolicyTest {

    @Test
    fun mergeServerTrailWithLiveOverlay_keepsOnlyAllowedNewerLivePoints() {
        val serverTrail = listOf(point("local", time = 100L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY))
        val currentTrail = listOf(
            point("local", time = 90L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS),
            point("local", time = 120L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS),
            point("other", time = 130L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS),
        )

        val merged = TrackerMapTrailMergePolicy.mergeServerTrailWithLiveOverlay(
            serverTrail = serverTrail,
            currentTrail = currentTrail,
            allowedLiveOverlayTrackerIds = setOf("local"),
            trailPointLimit = 10,
        )

        assertEquals(listOf(100L, 120L), merged.map { it.time })
        assertEquals(listOf("local", "local"), merged.map { it.trackerId })
    }

    @Test
    fun mergeServerTrailsWithLiveOverlays_dropsOutOfScopeCurrentTrackers() {
        val merged = TrackerMapTrailMergePolicy.mergeServerTrailsWithLiveOverlays(
            serverTrails = mapOf(
                "a" to listOf(point("a", time = 100L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY)),
            ),
            currentTrails = mapOf(
                "a" to listOf(point("a", time = 110L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_REMOTE_STREAM)),
                "old" to listOf(point("old", time = 120L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_REMOTE_STREAM)),
            ),
            allowedLiveOverlayTrackerIds = setOf("a"),
            trailPointLimit = 10,
        )

        assertEquals(setOf("a"), merged.keys)
        assertEquals(listOf(100L, 110L), merged.getValue("a").map { it.time })
        assertFalse(merged.containsKey("old"))
    }

    @Test
    fun mergeServerTrailWithLiveOverlay_localQueueAsServer_noNewerOverlay_preservesQueueHistory() {
        // RESTORE-AFTER-STREAMING REGRESSION GUARD (Bug 2): when restoring the selected (local)
        // tracker after a group stream stops, the freshly-loaded DB queue rows are fed into
        // mergeServerTrailWithLiveOverlay as the serverTrail. They are PROVENANCE_LOCAL_GPS
        // (which counts as live overlay), but with no newer live points the merge must short
        // circuit via `if (liveBuffer.isEmpty()) return serverTrail` and preserve the full
        // queue history rather than running the strip-isLiveOverlay path.
        val serverQueue = listOf(
            point("local", time = 50L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS),
            point("local", time = 60L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS),
            point("local", time = 70L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS),
        )

        val merged = TrackerMapTrailMergePolicy.mergeServerTrailWithLiveOverlay(
            serverTrail = serverQueue,
            currentTrail = emptyList(),
            allowedLiveOverlayTrackerIds = setOf("local"),
            trailPointLimit = 10,
        )

        assertEquals(listOf(50L, 60L, 70L), merged.map { it.time })
    }

    @Test
    fun mergeServerTrailWithLiveOverlay_serverGeometryWithLocalOverlay_appendsNewerLocalRows() {
        // RESTORE-AFTER-STREAMING POSITIVE GUARD (Bug 2): the standard SINGLE_SERVER path loads
        // PROVENANCE_SERVER_GEOMETRY rows for the displayed tracker and pairs them with the
        // local DB queue as live overlay candidates. Server rows must be preserved verbatim
        // (they are not live overlay), and any queue rows newer than the latest server time
        // must be stitched on top of the trail tail.
        val serverGeometry = listOf(
            point("local", time = 50L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY),
            point("local", time = 60L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY),
            point("local", time = 70L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY),
        )
        val liveOverlayCandidates = listOf(
            point("local", time = 65L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS),
            point("local", time = 80L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS),
        )

        val merged = TrackerMapTrailMergePolicy.mergeServerTrailWithLiveOverlay(
            serverTrail = serverGeometry,
            currentTrail = liveOverlayCandidates,
            allowedLiveOverlayTrackerIds = setOf("local"),
            trailPointLimit = 10,
        )

        assertEquals(listOf(50L, 60L, 70L, 80L), merged.map { it.time })
    }

    private fun point(
        trackerId: String,
        time: Long,
        prov: String,
    ): QueuedLocation {
        return QueuedLocation(
            id = if (prov == TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY) -time else 0L,
            trackerId = trackerId,
            time = time,
            latitude = time.toDouble() / 1000.0,
            longitude = time.toDouble() / 1000.0,
            altitude = null,
            speed = null,
            bearing = null,
            accuracy = null,
            sat = null,
            prov = prov,
            dist = null,
        )
    }
}
