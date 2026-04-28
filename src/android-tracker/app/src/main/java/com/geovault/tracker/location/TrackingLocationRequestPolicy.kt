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
    private const val MAX_NORMAL_REQUEST_DEFER_MS = 60_000L
    private const val FAST_GPS_LOCK_INTERVAL_MS = 0L
    private const val FAST_GPS_LOCK_MIN_UPDATE_INTERVAL_MS = 0L
    private const val FAST_GPS_LOCK_MIN_DISTANCE_METERS = 0f

    fun buildNormalRequest(input: TrackingLocationRequestInput): LocationRequest {
        val (intervalMs, minUpdateMs) = TrackingLocationPolicy.locationRequestIntervalFromSec(input.intervalSec)
        return LocationRequest.Builder(input.priority, intervalMs)
            .setMinUpdateDistanceMeters(input.distanceFilterMeters.coerceAtLeast(0f))
            .setMinUpdateIntervalMillis(minUpdateMs)
            .setMaxUpdateDelayMillis(normalRequestMaxDelayMs(input.intervalSec))
            .build()
    }

    fun buildFastLockRequest(): LocationRequest {
        return LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, FAST_GPS_LOCK_INTERVAL_MS)
            .setMinUpdateIntervalMillis(FAST_GPS_LOCK_MIN_UPDATE_INTERVAL_MS)
            .setMinUpdateDistanceMeters(FAST_GPS_LOCK_MIN_DISTANCE_METERS)
            .setWaitForAccurateLocation(true)
            .build()
    }

    fun normalRequestMaxDelayMs(intervalSec: Long): Long {
        val (intervalMs, _) = TrackingLocationPolicy.locationRequestIntervalFromSec(intervalSec)
        val candidate = intervalMs * 3L
        return candidate.coerceIn(intervalMs, MAX_NORMAL_REQUEST_DEFER_MS)
    }
}
