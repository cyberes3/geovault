package com.geovault.tracker.tracking

data class LocationRequestKey(
    val intervalSec: Long,
    val distanceFilterMeters: Float,
    val fastLock: Boolean,
)
