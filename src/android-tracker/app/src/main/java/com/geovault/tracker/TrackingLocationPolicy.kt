package com.geovault.tracker

import android.location.Location

/**
 * Pure logic for accepting locations and stationary detection.
 * Extracted so it can be unit tested without Service/Context.
 */
object TrackingLocationPolicy {
    const val MIN_STATIONARY_RADIUS_METERS = 25f
    const val DEFAULT_STATIONARY_RADIUS_METERS = 50f

    /**
     * Maximum fix accuracy (meters) at which the stationary counter is allowed
     * to advance. Beyond this, a fix is too noisy to be evidence of being
     * still: a "tight" cluster of bad-accuracy fixes has nothing to do with
     * the user actually standing in one place.
     */
    const val STATIONARY_ACCURACY_CEILING_METERS = 35f
    const val AUTO_START_PROFILE_INDEX = 0
    const val WALKING_INTERVAL_SEC = 20L
    const val WALKING_DISTANCE_FILTER_METERS = 7f
    const val WALKING_ACCURACY_FILTER_METERS = 40f
    const val BIKING_INTERVAL_SEC = 15L
    const val BIKING_DISTANCE_FILTER_METERS = 30f
    const val BIKING_ACCURACY_FILTER_METERS = 100f
    const val DRIVING_INTERVAL_SEC = 10L
    const val DRIVING_DISTANCE_FILTER_METERS = 100f
    const val DRIVING_ACCURACY_FILTER_METERS = 200f

    @JvmStatic
    fun getAutoStartProfileIndex(): Int = AUTO_START_PROFILE_INDEX

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
     * Outcome of a single [stationaryUpdate] call. [consecutive] is the next
     * counter value, [shouldPause] is true when GPS may be paused, and
     * [reason] is a stable identifier suitable for telemetry.
     *
     * "Hold" reasons leave the counter unchanged (neither resetting nor
     * advancing) - they describe situations where the fix is too noisy to
     * be evidence about stationarity in either direction.
     */
    data class StationaryDecision(
        val consecutive: Int,
        val shouldPause: Boolean,
        val reason: String,
    )

    /**
     * Updates stationary state from a single accepted fix.
     *
     * Pause requires three independent pieces of evidence to all agree on
     * "stationary":
     *
     *  1. The user has opted into GPS-pause behavior ([significantMotionOnly]).
     *  2. The fix was not adjusted by the upstream filter
     *     ([filterIntervened] = false). A snap-to-anchor adjustment carries
     *     no positional information about the user, only about fix quality.
     *  3. The fix is sufficiently accurate ([accuracyCeilingMeters]) that
     *     "no displacement against the anchor" is meaningful. A tight cluster
     *     of bad-accuracy fixes is *not* evidence of stationarity.
     *
     * When (2) or (3) fails the counter is held (not reset, not advanced):
     * a transient noisy fix should neither erase legitimate progress nor
     * push us into a false pause.
     */
    @JvmStatic
    @JvmOverloads
    fun stationaryUpdate(
        lastLocation: Location?,
        location: Location,
        stationaryRadiusMeters: Float,
        currentConsecutive: Int,
        significantMotionOnly: Boolean,
        activeMotionHint: Boolean = false,
        filterIntervened: Boolean = false,
        accuracyCeilingMeters: Float = STATIONARY_ACCURACY_CEILING_METERS,
    ): StationaryDecision {
        if (!significantMotionOnly) {
            return StationaryDecision(consecutive = 0, shouldPause = false, reason = "disabled")
        }
        if (activeMotionHint) {
            return StationaryDecision(consecutive = 0, shouldPause = false, reason = "active_motion_hint")
        }
        if (location.hasSpeed() && location.speed > 0.75f) {
            return StationaryDecision(consecutive = 0, shouldPause = false, reason = "gps_speed_movement")
        }
        if (filterIntervened) {
            return StationaryDecision(
                consecutive = currentConsecutive,
                shouldPause = false,
                reason = "filter_intervened",
            )
        }
        if (location.hasAccuracy() && location.accuracy > accuracyCeilingMeters) {
            return StationaryDecision(
                consecutive = currentConsecutive,
                shouldPause = false,
                reason = "poor_accuracy",
            )
        }

        val anchor = lastLocation
            ?: return StationaryDecision(consecutive = 1, shouldPause = false, reason = "first_anchor")
        val radius = stationaryRadiusMeters.coerceAtLeast(MIN_STATIONARY_RADIUS_METERS)
        val dist = anchor.distanceTo(location)
        val withinRadius = dist <= radius
        val newConsecutive = if (withinRadius) currentConsecutive + 1 else 1
        val shouldPause = newConsecutive >= 3
        val reason = when {
            shouldPause -> "pause_threshold_reached"
            withinRadius -> "advance_within_radius"
            else -> "reset_outside_radius"
        }
        return StationaryDecision(consecutive = newConsecutive, shouldPause = shouldPause, reason = reason)
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
            0 -> Triple(WALKING_INTERVAL_SEC, WALKING_DISTANCE_FILTER_METERS, WALKING_ACCURACY_FILTER_METERS)
            1 -> Triple(BIKING_INTERVAL_SEC, BIKING_DISTANCE_FILTER_METERS, BIKING_ACCURACY_FILTER_METERS)
            2 -> Triple(DRIVING_INTERVAL_SEC, DRIVING_DISTANCE_FILTER_METERS, DRIVING_ACCURACY_FILTER_METERS)
            else -> Triple(BIKING_INTERVAL_SEC, BIKING_DISTANCE_FILTER_METERS, BIKING_ACCURACY_FILTER_METERS)
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
