package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapPointProvenancePolicyTest {

    @Test
    fun isLiveOverlay_negativeIdServerGeometry_isHistorical() {
        val point = queued(
            id = -1L,
            prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY,
        )

        assertFalse(TrackerMapPointProvenancePolicy.isLiveOverlay(point))
    }

    @Test
    fun isLiveOverlay_runtimeLocalAndRemoteStream_areLive() {
        assertTrue(
            TrackerMapPointProvenancePolicy.isLiveOverlay(
                queued(id = 0L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS_RUNTIME)
            )
        )
        assertTrue(
            TrackerMapPointProvenancePolicy.isLiveOverlay(
                queued(id = 0L, prov = TrackerMapPointProvenancePolicy.PROVENANCE_REMOTE_STREAM)
            )
        )
    }

    private fun queued(id: Long, prov: String): QueuedLocation {
        return QueuedLocation(
            id = id,
            trackerId = "tracker",
            time = 1L,
            latitude = 1.0,
            longitude = 1.0,
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
