package com.geovault.tracker.pipeline

enum class TrackPointSource {
    LOCAL_GPS,
    REMOTE_STREAM
}

data class TrackPointEvent(
    val source: TrackPointSource,
    val trackId: String,
    val lon: Double,
    val lat: Double,
    val timestampMs: Long,
    val accuracyMeters: Float? = null,
    val propsJson: String? = null
)
