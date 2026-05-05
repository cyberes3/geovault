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
