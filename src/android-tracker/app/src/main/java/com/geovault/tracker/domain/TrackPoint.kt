package com.geovault.tracker.domain

import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.policy.TrackPointQuality
import com.geovault.tracker.policy.TrackPointSource

data class TrackPoint(
    val trackerId: String,
    val timeMs: Long,
    val latitude: Double,
    val longitude: Double,
    val quality: TrackPointQuality = TrackPointQuality.HIGH_CONFIDENCE,
    val provenance: TrackPointSource,
    val sessionId: Long? = null,
    val accuracyMeters: Float? = null,
    val propsJson: String? = null,
    val orderingKey: Long = 0L,
    val elapsedRealtimeNanos: Long? = null,
    val gpsSpeedMps: Float? = null,
    val gpsBearingDeg: Float? = null,
) {
    fun toQueuedLocation(
        rowId: Long = 0L,
        altitude: Double? = null,
        satellites: Int? = null,
        provider: String? = null,
        distanceMeters: Float? = null,
        startTimestampMs: Long? = sessionId,
    ): QueuedLocation {
        return QueuedLocation(
            id = rowId,
            trackerId = trackerId,
            time = timeMs,
            latitude = latitude,
            longitude = longitude,
            altitude = altitude,
            speed = gpsSpeedMps,
            bearing = gpsBearingDeg,
            accuracy = accuracyMeters,
            sat = satellites,
            prov = provider,
            dist = distanceMeters,
            startTimestampMs = startTimestampMs,
        )
    }

    companion object {
        fun fromQueuedLocation(
            row: QueuedLocation,
            provenance: TrackPointSource,
            quality: TrackPointQuality = TrackPointQuality.HIGH_CONFIDENCE,
        ): TrackPoint {
            return TrackPoint(
                trackerId = row.trackerId,
                timeMs = row.time,
                latitude = row.latitude,
                longitude = row.longitude,
                quality = quality,
                provenance = provenance,
                sessionId = row.startTimestampMs,
                accuracyMeters = row.accuracy,
                gpsSpeedMps = row.speed,
                gpsBearingDeg = row.bearing,
            )
        }
    }
}
