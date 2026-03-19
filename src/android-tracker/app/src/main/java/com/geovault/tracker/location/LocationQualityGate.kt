package com.geovault.tracker.location

import android.location.Location
import kotlin.math.abs

enum class LocationRejectionReason {
    STALE,
    BAD_ACCURACY,
    INVALID_COORDINATES,
    JUMP
}

data class LocationQualityConfig(
    val maxAccuracyMeters: Float,
    val maxJumpSpeedMps: Double,
    val freshnessTtlMs: Long,
    val smoothingAlpha: Float = 0.5f
)

data class LocationQualityResult(
    val accepted: Boolean,
    val location: Location,
    val rejectionReason: LocationRejectionReason? = null
)

object LocationQualityGate {
    fun isFresh(location: Location, nowMs: Long, freshnessTtlMs: Long): Boolean {
        if (freshnessTtlMs <= 0L) return true
        val timestamp = location.time
        if (timestamp <= 0L) return false
        val ageMs = nowMs - timestamp
        return ageMs in 0..freshnessTtlMs
    }

    fun evaluate(
        lastAcceptedLocation: Location?,
        newLocation: Location,
        nowMs: Long,
        config: LocationQualityConfig
    ): LocationQualityResult {
        if (newLocation.latitude !in -90.0..90.0 || newLocation.longitude !in -180.0..180.0) {
            return LocationQualityResult(false, newLocation, LocationRejectionReason.INVALID_COORDINATES)
        }
        if (!isFresh(newLocation, nowMs, config.freshnessTtlMs)) {
            return LocationQualityResult(false, newLocation, LocationRejectionReason.STALE)
        }
        if (newLocation.hasAccuracy() && newLocation.accuracy > config.maxAccuracyMeters) {
            return LocationQualityResult(false, newLocation, LocationRejectionReason.BAD_ACCURACY)
        }
        if (isJump(lastAcceptedLocation, newLocation, config.maxJumpSpeedMps)) {
            return LocationQualityResult(false, newLocation, LocationRejectionReason.JUMP)
        }
        val smoothed = smooth(lastAcceptedLocation, newLocation, config.smoothingAlpha)
        return LocationQualityResult(true, smoothed)
    }

    private fun isJump(lastLocation: Location?, newLocation: Location, maxJumpSpeedMps: Double): Boolean {
        if (lastLocation == null) return false
        val timeDiffSec = (newLocation.time - lastLocation.time) / 1000.0
        if (timeDiffSec <= 0.0) return false
        val speed = lastLocation.distanceTo(newLocation) / timeDiffSec
        return speed > maxJumpSpeedMps
    }

    private fun smooth(lastLocation: Location?, newLocation: Location, alpha: Float): Location {
        if (lastLocation == null) return newLocation
        val boundedAlpha = alpha.coerceIn(0.05f, 0.95f)
        val smoothed = Location(newLocation)
        smoothed.latitude = (boundedAlpha * newLocation.latitude) + ((1f - boundedAlpha) * lastLocation.latitude)
        smoothed.longitude = (boundedAlpha * newLocation.longitude) + ((1f - boundedAlpha) * lastLocation.longitude)
        if (newLocation.hasAltitude() && lastLocation.hasAltitude()) {
            smoothed.altitude = (boundedAlpha * newLocation.altitude) + ((1f - boundedAlpha) * lastLocation.altitude)
        }
        if (newLocation.hasBearing() && lastLocation.hasBearing()) {
            val diff = (((newLocation.bearing - lastLocation.bearing + 540f) % 360f) - 180f)
            smoothed.bearing = ((lastLocation.bearing + boundedAlpha * diff) + 360f) % 360f
        }
        if (newLocation.hasSpeed() && lastLocation.hasSpeed()) {
            smoothed.speed = (boundedAlpha * newLocation.speed) + ((1f - boundedAlpha) * lastLocation.speed)
        }
        return smoothed
    }
}
