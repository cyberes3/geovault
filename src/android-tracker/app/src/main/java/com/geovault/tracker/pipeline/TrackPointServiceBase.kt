package com.geovault.tracker.pipeline

import android.app.Service

abstract class TrackPointServiceBase : Service() {
    protected fun publishTrackPoint(
        source: TrackPointSource,
        trackId: String,
        lon: Double,
        lat: Double,
        timestampMs: Long,
        accuracyMeters: Float?,
        propsJson: String?
    ) {
        TrackPointBus.publish(
            TrackPointEvent(
                source = source,
                trackId = trackId,
                lon = lon,
                lat = lat,
                timestampMs = timestampMs,
                accuracyMeters = accuracyMeters,
                propsJson = propsJson
            )
        )
    }
}
