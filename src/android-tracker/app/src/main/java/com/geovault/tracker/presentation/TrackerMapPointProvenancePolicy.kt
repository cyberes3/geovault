package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation

object TrackerMapPointProvenancePolicy {
    const val PROVENANCE_LOCAL_GPS = "local_gps"
    const val PROVENANCE_LOCAL_GPS_RUNTIME = "local_gps_runtime"
    const val PROVENANCE_REMOTE_STREAM = "remote_stream"
    const val PROVENANCE_SERVER_GEOMETRY = "server_geometry"

    fun isLiveOverlay(point: QueuedLocation): Boolean {
        return when (point.prov?.trim()) {
            PROVENANCE_LOCAL_GPS,
            PROVENANCE_LOCAL_GPS_RUNTIME,
            PROVENANCE_REMOTE_STREAM -> true
            else -> false
        }
    }
}
