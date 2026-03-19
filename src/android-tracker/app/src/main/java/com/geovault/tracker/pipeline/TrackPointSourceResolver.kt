package com.geovault.tracker.pipeline

object TrackPointSourceResolver {
    fun mapDataSourceMode(trackingRunning: Boolean): MapDataSourceMode {
        return if (trackingRunning) {
            MapDataSourceMode.LOCAL_GPS_ONLY
        } else {
            MapDataSourceMode.REMOTE_STREAM_ONLY
        }
    }

    fun shouldAcceptForParams(
        event: TrackPointEvent,
        trackerId: String,
        trackingRunning: Boolean,
        selectedTrackerId: String
    ): Boolean {
        if (event.trackId != trackerId) return false
        val mode = mapDataSourceMode(trackingRunning && trackerId == selectedTrackerId)
        return when (mode) {
            MapDataSourceMode.LOCAL_GPS_ONLY -> event.source == TrackPointSource.LOCAL_GPS
            MapDataSourceMode.REMOTE_STREAM_ONLY -> event.source == TrackPointSource.REMOTE_STREAM
        }
    }
}

enum class MapDataSourceMode {
    LOCAL_GPS_ONLY,
    REMOTE_STREAM_ONLY
}
