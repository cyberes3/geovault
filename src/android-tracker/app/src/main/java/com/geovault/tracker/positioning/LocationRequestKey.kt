package com.geovault.tracker.positioning

data class LocationRequestKey(
    val intervalSec: Long,
    val distanceFilterMeters: Float,
    val fastLock: Boolean,
)
