package com.geovault.tracker.policy

enum class TrackPointSource {
    LOCAL_GPS,
    REMOTE_STREAM
}

enum class TrackPointQuality {
    HIGH_CONFIDENCE,
    DEGRADED
}

data class TrackPointEvent(
    val source: TrackPointSource,
    val trackId: String,
    val lon: Double,
    val lat: Double,
    val timestampMs: Long,
    val accuracyMeters: Float? = null,
    val propsJson: String? = null,
    val quality: TrackPointQuality = TrackPointQuality.HIGH_CONFIDENCE,
    val orderingKey: Long = 0L,
    val elapsedRealtimeNanos: Long? = null,
    val gpsSpeedMps: Float? = null,
    val gpsBearingDeg: Float? = null,
)
