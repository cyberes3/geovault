package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import com.geovault.tracker.map.MapTrailEngine
class TrackerMapPointProvenancePolicyTest {

    @Test
    fun isLiveOverlay_negativeIdServerGeometry_isHistorical() {
        val point = queued(
            id = -1L,
            prov = MapTrailEngine.PROVENANCE_SERVER_GEOMETRY,
        )

        assertFalse(MapTrailEngine.isLiveOverlay(point))
    }

    @Test
    fun isLiveOverlay_runtimeLocalAndRemoteStream_areLive() {
        assertTrue(
            MapTrailEngine.isLiveOverlay(
                queued(id = 0L, prov = MapTrailEngine.PROVENANCE_LOCAL_GPS_RUNTIME)
            )
        )
        assertTrue(
            MapTrailEngine.isLiveOverlay(
                queued(id = 0L, prov = MapTrailEngine.PROVENANCE_REMOTE_STREAM)
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
