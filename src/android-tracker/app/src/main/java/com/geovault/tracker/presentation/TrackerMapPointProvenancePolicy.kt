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

    /**
     * Server-side history is the authoritative trail for a tracker. The streaming-resume
     * short-circuit uses this to decide whether the in-memory roster has a real loaded trail
     * (vs only a sliver of local-queue or live-stream rows that a prior reload happened to
     * leave behind). Only [PROVENANCE_SERVER_GEOMETRY] qualifies; everything else is overlay.
     */
    fun isServerHistory(point: QueuedLocation): Boolean {
        return point.prov?.trim() == PROVENANCE_SERVER_GEOMETRY
    }
}
