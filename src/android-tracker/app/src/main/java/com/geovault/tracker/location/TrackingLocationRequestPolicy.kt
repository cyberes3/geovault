package com.geovault.tracker.location

import com.geovault.tracker.TrackingLocationPolicy
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority

data class TrackingLocationRequestInput(
    val intervalSec: Long,
    val distanceFilterMeters: Float,
    val priority: Int = Priority.PRIORITY_HIGH_ACCURACY,
)

object TrackingLocationRequestPolicy {
    private const val NORMAL_REQUEST_MAX_DELAY_MS = 0L
    private const val FAST_GPS_LOCK_INTERVAL_MS = 0L
    private const val FAST_GPS_LOCK_MIN_UPDATE_INTERVAL_MS = 0L
    private const val FAST_GPS_LOCK_MIN_DISTANCE_METERS = 0f

    fun buildNormalRequest(input: TrackingLocationRequestInput): LocationRequest {
        val (intervalMs, minUpdateMs) = TrackingLocationPolicy.locationRequestIntervalFromSec(input.intervalSec)
        return LocationRequest.Builder(input.priority, intervalMs)
            .setMinUpdateDistanceMeters(input.distanceFilterMeters.coerceAtLeast(0f))
            .setMinUpdateIntervalMillis(minUpdateMs)
            .setMaxUpdateDelayMillis(NORMAL_REQUEST_MAX_DELAY_MS)
            .build()
    }

    fun buildFastLockRequest(): LocationRequest {
        return LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, FAST_GPS_LOCK_INTERVAL_MS)
            .setMinUpdateIntervalMillis(FAST_GPS_LOCK_MIN_UPDATE_INTERVAL_MS)
            .setMinUpdateDistanceMeters(FAST_GPS_LOCK_MIN_DISTANCE_METERS)
            .setWaitForAccurateLocation(true)
            .build()
    }

}
