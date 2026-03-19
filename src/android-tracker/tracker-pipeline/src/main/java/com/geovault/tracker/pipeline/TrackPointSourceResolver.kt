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
        val localMode = trackingRunning && trackerId == selectedTrackerId
        return when (event.source) {
            TrackPointSource.LOCAL_GPS -> localMode
            TrackPointSource.REMOTE_STREAM -> !localMode
        }
    }
}

enum class MapDataSourceMode {
    LOCAL_GPS_ONLY,
    REMOTE_STREAM_ONLY
}

