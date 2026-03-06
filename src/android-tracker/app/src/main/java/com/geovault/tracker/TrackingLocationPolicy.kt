package com.geovault.tracker

import android.location.Location

/**
 * Pure logic for accepting locations and stationary detection.
 * Extracted so it can be unit tested without Service/Context.
 */
object TrackingLocationPolicy {

    /**
     * Returns true if the location passes the accuracy filter (should be queued).
     * Accept when location has no accuracy or when accuracy <= threshold.
     */
    fun acceptByAccuracy(location: Location, accuracyFilter: Float): Boolean {
        if (!location.hasAccuracy()) return true
        return location.accuracy <= accuracyFilter
    }

    /**
     * Updates stationary state. When significantMotionOnly is true and we have
     * 3 consecutive points within distanceFilter, shouldPause is true.
     *
     * @return Pair(newConsecutiveStationaryCount, shouldPauseGps)
     */
    fun stationaryUpdate(
        lastLocation: Location?,
        location: Location,
        distanceFilter: Float,
        currentConsecutive: Int,
        significantMotionOnly: Boolean
    ): Pair<Int, Boolean> {
        if (!significantMotionOnly) return 0 to false
        val dist = lastLocation?.distanceTo(location) ?: Float.MAX_VALUE
        val newConsecutive = if (dist < distanceFilter) currentConsecutive + 1 else 0
        val shouldPause = newConsecutive >= 3
        return newConsecutive to shouldPause
    }

    /**
     * Returns (intervalMillis, minUpdateIntervalMillis) for LocationRequest from interval in seconds.
     * Used so we can unit test that prefs interval is correctly converted.
     */
    fun locationRequestIntervalFromSec(intervalSec: Long): Pair<Long, Long> {
        val intervalMs = intervalSec * 1000L
        val minUpdateMs = intervalMs / 2
        return intervalMs to minUpdateMs
    }
}
