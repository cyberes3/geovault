package com.geovault.common.geo

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val latRad1 = Math.toRadians(lat1)
        val latRad2 = Math.toRadians(lat2)
        val dLat = latRad2 - latRad1
        val dLon = Math.toRadians(lon2 - lon1)
        val sinHalfLat = sin(dLat / 2.0)
        val sinHalfLon = sin(dLon / 2.0)
        val a = (sinHalfLat * sinHalfLat) + (cos(latRad1) * cos(latRad2) * sinHalfLon * sinHalfLon)
        val bounded = a.coerceIn(0.0, 1.0)
        return EARTH_RADIUS_M * 2.0 * asin(sqrt(bounded))
    }

    /**
     * Shortest-arc absolute bearing change in degrees, 0..180.
     */
    fun shortestBearingDeltaDegrees(fromDeg: Double, toDeg: Double): Double {
        val rawDelta = abs(toDeg - fromDeg) % 360.0
        return if (rawDelta > 180.0) 360.0 - rawDelta else rawDelta
    }
}
package com.geovault.common.geo

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val latRad1 = Math.toRadians(lat1)
        val latRad2 = Math.toRadians(lat2)
        val dLat = latRad2 - latRad1
        val dLon = Math.toRadians(lon2 - lon1)
        val sinHalfLat = sin(dLat / 2.0)
        val sinHalfLon = sin(dLon / 2.0)
        val a = (sinHalfLat * sinHalfLat) + (cos(latRad1) * cos(latRad2) * sinHalfLon * sinHalfLon)
        val bounded = a.coerceIn(0.0, 1.0)
        return EARTH_RADIUS_M * 2.0 * asin(sqrt(bounded))
    }

    /**
     * Shortest-arc absolute bearing change in degrees, 0..180.
     */
    fun shortestBearingDeltaDegrees(fromDeg: Double, toDeg: Double): Double {
        val rawDelta = abs(toDeg - fromDeg) % 360.0
        return if (rawDelta > 180.0) 360.0 - rawDelta else rawDelta
    }
}
