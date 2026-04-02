package com.geovault.common.maps.core

import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds

/**
 * Builds a [LatLngBounds] that contains all [points] using the shorter geographic longitude arc.
 *
 * [LatLngBounds.Builder] uses naive min/max longitude. When points sit on opposite sides of the
 * Pacific (e.g. USA and Russia), that yields a >180° box and a poor camera fit. This helper picks
 * the minimal longitude span on the circle and uses MapLibre’s extended longitude when the span
 * crosses the antimeridian ([longitudeEast] may exceed 180).
 */
fun geoVaultLatLngBoundsForPoints(points: List<LatLng>): LatLngBounds? {
    if (points.isEmpty()) return null
    if (points.size == 1) {
        val p = points.first()
        return LatLngBounds.from(p.latitude, p.longitude, p.latitude, p.longitude)
    }
    val minLat = points.minOf { it.latitude }
    val maxLat = points.maxOf { it.latitude }
    val (lonWest, lonEast) = geoVaultMinimalLongitudeSpanBounds(points.map { it.longitude })
    return LatLngBounds.from(maxLat, lonEast, minLat, lonWest)
}

/**
 * Expands [bounds] to include [additional] using the same antimeridian-aware logic as
 * [geoVaultLatLngBoundsForPoints] (unlike [LatLngBounds.Builder], which uses naive min/max longitude).
 */
fun geoVaultLatLngBoundsUnion(bounds: LatLngBounds, additional: Collection<LatLng>): LatLngBounds {
    if (additional.isEmpty()) return bounds
    val corners = listOf(bounds.southWest, bounds.northEast)
    return geoVaultLatLngBoundsForPoints(corners + additional.toList()) ?: bounds
}

private fun longitudeTo360(degrees: Double): Double {
    var x = degrees % 360.0
    if (x < 0) x += 360.0
    return x
}

internal fun geoVaultMinimalLongitudeSpanBounds(longitudes: List<Double>): Pair<Double, Double> {
    if (longitudes.isEmpty()) return Pair(-180.0, 180.0)
    if (longitudes.size == 1) {
        val l = longitudes.first()
        return Pair(l, l)
    }
    val sorted = longitudes.map { longitudeTo360(it) }.distinct().sorted()
    if (sorted.size == 1) {
        val only = sorted.first()
        val l = ((only + 180.0) % 360.0) - 180.0
        return Pair(l, l)
    }
    var maxGap = -1.0
    var maxGapIndex = 0
    for (i in sorted.indices) {
        val next = (i + 1) % sorted.size
        val gap = if (next == 0) {
            360.0 - sorted[i] + sorted[0]
        } else {
            sorted[next] - sorted[i]
        }
        if (gap > maxGap) {
            maxGap = gap
            maxGapIndex = i
        }
    }
    val nextIdx = (maxGapIndex + 1) % sorted.size
    var west360 = sorted[nextIdx]
    var east360 = sorted[maxGapIndex]
    if (east360 < west360) {
        east360 += 360.0
    }
    check(east360 - west360 <= 180.0 + 1e-6) {
        "minimal longitude arc should be at most 180°; west360=$west360 east360=$east360"
    }
    val lonWest: Double
    val lonEast: Double
    when {
        east360 <= 180.0 -> {
            lonWest = west360
            lonEast = east360
        }
        west360 >= 180.0 -> {
            lonWest = west360 - 360.0
            lonEast = east360 - 360.0
        }
        else -> {
            lonWest = west360
            lonEast = east360
        }
    }
    return Pair(lonWest, lonEast)
}
