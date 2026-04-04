package com.geovault.common.maps.core

import android.location.Location
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

fun geoVaultSplitTrackByDistance(
    points: List<Pair<Double, Double>>,
    maxJumpMeters: Float
): List<List<Pair<Double, Double>>> {
    if (maxJumpMeters <= 0f) return listOf(points.filter(::isFiniteLatLon))
    val valid = points.filter(::isFiniteLatLon)
    if (valid.size < 2) return emptyList()
    val segments = mutableListOf<MutableList<Pair<Double, Double>>>()
    var current = mutableListOf(valid.first())
    for (index in 1 until valid.size) {
        val previous = valid[index - 1]
        val next = valid[index]
        val distanceMeters = androidDistanceMeters(
            lat1 = previous.first,
            lon1 = previous.second,
            lat2 = next.first,
            lon2 = next.second
        )
        if (distanceMeters > maxJumpMeters) {
            if (current.size >= 2) segments.add(current)
            current = mutableListOf(next)
        } else {
            current.add(next)
        }
    }
    if (current.size >= 2) segments.add(current)
    return segments
}

private fun androidDistanceMeters(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double
): Float {
    return try {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        results[0]
    } catch (_: RuntimeException) {
        // JVM unit tests don't have Android framework distance implementations.
        haversineDistanceMeters(lat1 = lat1, lon1 = lon1, lat2 = lat2, lon2 = lon2)
    }
}

private fun haversineDistanceMeters(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double
): Float {
    val radiusMeters = 6_378_137.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return (radiusMeters * c).toFloat()
}

private fun isFiniteLatLon(point: Pair<Double, Double>): Boolean {
    val lat = point.first
    val lon = point.second
    return lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0
}
