package com.geovault.common.maps.location

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class AccuracyRadiusInput(
    val streamedAccuracyMeters: Float?,
    val fallbackAccuracyMeters: Float?,
    val allowFallback: Boolean,
)

object AccuracyRadiusPolicy {
    fun resolveAccuracyRadiusMeters(input: AccuracyRadiusInput): Double {
        val streamed = sanitizeMeters(input.streamedAccuracyMeters)
        if (streamed != null) {
            return streamed.toDouble()
        }
        if (!input.allowFallback) {
            return 0.0
        }
        return sanitizeMeters(input.fallbackAccuracyMeters)?.toDouble() ?: 0.0
    }

    private fun sanitizeMeters(value: Float?): Float? {
        return value?.takeIf { it.isFinite() && it > 0f }
    }
}

data class LatLon(val lat: Double, val lon: Double)

object AccuracyGeometryBuilder {
    private const val EARTH_RADIUS_METERS = 6378137.0

    /**
     * Builds polygon points around [center] for an accuracy-radius ring.
     * Returns empty when [radiusMeters] is invalid.
     */
    fun buildAccuracyRing(center: LatLon, radiusMeters: Double, steps: Int = 36): List<LatLon> {
        if (!radiusMeters.isFinite() || radiusMeters <= 0.0 || steps < 8) {
            return emptyList()
        }
        val latRad = Math.toRadians(center.lat)
        val ring = ArrayList<LatLon>(steps + 1)
        for (i in 0..steps) {
            val a = (i.toDouble() / steps) * 2.0 * PI
            val north = radiusMeters * cos(a)
            val east = radiusMeters * sin(a)
            val dLat = north / EARTH_RADIUS_METERS
            val dLon = east / (EARTH_RADIUS_METERS * cos(latRad))
            ring.add(
                LatLon(
                    lat = center.lat + Math.toDegrees(dLat),
                    lon = center.lon + Math.toDegrees(dLon),
                )
            )
        }
        return ring
    }
}
