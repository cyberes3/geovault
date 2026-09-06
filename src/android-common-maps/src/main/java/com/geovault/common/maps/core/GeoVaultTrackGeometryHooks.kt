package com.geovault.common.maps.core

import com.geovault.common.geo.GeoMath
import com.geovault.common.geo.Wgs84Point

fun geoVaultSplitTrackByDistance(
    points: List<Wgs84Point>,
    maxJumpMeters: Float
): List<List<Wgs84Point>> {
    if (maxJumpMeters <= 0f) return listOf(points.filter { it.isValidGeographic() })
    val valid = points.filter { it.isValidGeographic() }
    if (valid.size < 2) return emptyList()
    val segments = mutableListOf<MutableList<Wgs84Point>>()
    var current = mutableListOf(valid.first())
    for (index in 1 until valid.size) {
        val previous = valid[index - 1]
        val next = valid[index]
        val distanceMeters = GeoMath.haversineMeters(previous, next)
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
