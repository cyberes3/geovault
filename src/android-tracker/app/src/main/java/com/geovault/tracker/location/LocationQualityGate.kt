package com.geovault.tracker.location

import android.location.Location
import com.geovault.tracker.pipeline.TrackPointEvent
import com.geovault.tracker.pipeline.TrackPointPolicyConfig
import com.geovault.tracker.pipeline.TrackPointPolicyEngine
import com.geovault.tracker.pipeline.TrackPointRejectReason
import com.geovault.tracker.pipeline.TrackPointSource

enum class LocationRejectionReason {
    STALE,
    BAD_ACCURACY,
    INVALID_COORDINATES,
    OUT_OF_ORDER,
    DUPLICATE,
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
        val previousEvent = lastAcceptedLocation?.let {
            TrackPointEvent(
                source = TrackPointSource.LOCAL_GPS,
                trackId = "local",
                lon = it.longitude,
                lat = it.latitude,
                timestampMs = it.time,
                accuracyMeters = if (it.hasAccuracy()) it.accuracy else null
            )
        }
        val currentEvent = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = "local",
            lon = newLocation.longitude,
            lat = newLocation.latitude,
            timestampMs = newLocation.time,
            accuracyMeters = if (newLocation.hasAccuracy()) newLocation.accuracy else null
        )
        val decision = TrackPointPolicyEngine.evaluate(
            event = currentEvent,
            previous = previousEvent,
            nowMs = nowMs,
            config = TrackPointPolicyConfig(
                maxAccuracyMeters = config.maxAccuracyMeters,
                degradedAccuracyMultiplier = 1f,
                maxFutureSkewMs = 5 * 60 * 1000L,
                maxJumpSpeedMps = config.maxJumpSpeedMps,
                freshnessTtlMs = config.freshnessTtlMs,
                normalizeSecondsTimestamps = false
            )
        )
        if (!decision.accepted) {
            return LocationQualityResult(
                accepted = false,
                location = newLocation,
                rejectionReason = when (decision.rejectReason) {
                    TrackPointRejectReason.INVALID_COORDINATES -> LocationRejectionReason.INVALID_COORDINATES
                    TrackPointRejectReason.OUT_OF_ORDER -> LocationRejectionReason.OUT_OF_ORDER
                    TrackPointRejectReason.DUPLICATE -> LocationRejectionReason.DUPLICATE
                    TrackPointRejectReason.BAD_ACCURACY -> LocationRejectionReason.BAD_ACCURACY
                    TrackPointRejectReason.STALE -> LocationRejectionReason.STALE
                    TrackPointRejectReason.JUMP -> LocationRejectionReason.JUMP
                    TrackPointRejectReason.TOO_FAR_FUTURE, null -> LocationRejectionReason.OUT_OF_ORDER
                }
            )
        }
        val normalized = Location(newLocation).apply {
            time = decision.canonicalEvent?.timestampMs ?: newLocation.time
        }
        val smoothed = smooth(lastAcceptedLocation, normalized, config.smoothingAlpha)
        return LocationQualityResult(true, smoothed)
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
