package com.geovault.common.maps.core

import com.geovault.common.geo.GeoMath

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
        val distanceMeters = GeoMath.haversineMeters(
            previous.first,
            previous.second,
            next.first,
            next.second,
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

private fun isFiniteLatLon(point: Pair<Double, Double>): Boolean {
    return isValidMapLibreGeographicLatLng(point.first, point.second)
}
