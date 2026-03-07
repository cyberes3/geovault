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
    @JvmStatic
    fun acceptByAccuracy(location: Location, accuracyFilter: Float): Boolean {
        if (!location.hasAccuracy()) return true
        return location.accuracy <= accuracyFilter
    }

    /**
     * Returns true if the change in location implies an unrealistic speed (> 100 m/s / ~223 mph).
     */
    @JvmStatic
    fun isJump(lastLocation: Location?, newLocation: Location): Boolean {
        if (lastLocation == null) return false
        val dist = lastLocation.distanceTo(newLocation)
        val timeDiff = (newLocation.time - lastLocation.time) / 1000.0 // seconds
        if (timeDiff <= 0) return false
        val speed = dist / timeDiff
        return speed > 100.0
    }

    /**
     * Applies Exponential Weighted Moving Average to smooth the coordinates.
     * alpha = 0.5 means 50% last, 50% new.
     */
    @JvmStatic
    @JvmOverloads
    fun smooth(lastLocation: Location?, newLocation: Location, alpha: Float = 0.5f): Location {
        if (lastLocation == null) return newLocation
        val smoothed = Location(newLocation)
        smoothed.latitude = (alpha * newLocation.latitude) + ((1 - alpha) * lastLocation.latitude)
        smoothed.longitude = (alpha * newLocation.longitude) + ((1 - alpha) * lastLocation.longitude)
        if (newLocation.hasAltitude() && lastLocation.hasAltitude()) {
            smoothed.altitude = (alpha * newLocation.altitude) + ((1 - alpha) * lastLocation.altitude)
        }
        return smoothed
    }

    /**
     * Updates stationary state. When significantMotionOnly is true and we have
     * 3 consecutive points within distanceFilter, shouldPause is true.
     *
     * @return Pair(newConsecutiveStationaryCount, shouldPauseGps)
     */
    @JvmStatic
    fun stationaryUpdate(
        lastLocation: Location?,
        location: Location,
        distanceFilter: Float,
        currentConsecutive: Int,
        significantMotionOnly: Boolean
    ): Pair<Int, Boolean> {
        if (!significantMotionOnly) return 0 to false

        // If hardware already detects speed > 1.5 m/s (~3.3 mph), we are not stationary
        if (location.hasSpeed() && location.speed > 1.5f) {
            return 0 to false
        }

        val dist = lastLocation?.distanceTo(location) ?: Float.MAX_VALUE
        val newConsecutive = if (dist < distanceFilter) currentConsecutive + 1 else 0
        val shouldPause = newConsecutive >= 3
        return newConsecutive to shouldPause
    }

    /**
     * Returns (intervalMillis, minUpdateIntervalMillis) for LocationRequest from interval in seconds.
     * Used so we can unit test that prefs interval is correctly converted.
     */
    @JvmStatic
    fun locationRequestIntervalFromSec(intervalSec: Long): Pair<Long, Long> {
        val intervalMs = intervalSec * 1000L
        val minUpdateMs = intervalMs / 2
        return intervalMs to minUpdateMs
    }

    /**
     * Profile indexes: 0: Walking, 1: Biking, 2: Driving
     * Returns: Triple(intervalSec, distanceFilter, accuracyFilter)
     */
    @JvmStatic
    fun getProfileParams(profileIndex: Int): Triple<Long, Float, Float> {
        return when (profileIndex) {
            0 -> Triple(30L, 10f, 50f)
            1 -> Triple(15L, 30f, 100f)
            2 -> Triple(10L, 100f, 200f)
            else -> Triple(15L, 30f, 100f) // Default to Biking
        }
    }

    /**
     * Determines the best profile based on speed (m/s) with hysteresis.
     * 
     * Thresholds (m/s):
     * Walking -> Biking: > 2.0 (7.2 km/h)
     * Biking -> Driving: > 8.0 (28.8 km/h)
     * Driving -> Biking: < 6.0 (21.6 km/h)
     * Biking -> Walking: < 1.5 (5.4 km/h)
     */
    @JvmStatic
    fun getRecommendedProfile(speedMps: Float, currentProfile: Int): Int {
        return when (currentProfile) {
            0 -> if (speedMps > 2.0f) 1 else 0
            1 -> when {
                speedMps > 8.0f -> 2
                speedMps < 1.5f -> 0
                else -> 1
            }
            2 -> if (speedMps < 6.0f) 1 else 2
            else -> 1 // Default to Biking if unknown
        }
    }
}
