package com.geovault.common.maps.render

import com.geovault.common.maps.core.isValidMapLibreGeographicLatLng

internal fun filterMapRenderPointsForGeoJson(points: List<MapRenderPoint>): List<MapRenderPoint> {
    return points.filter { isValidMapLibreGeographicLatLng(it.latitude, it.longitude) }
}

internal fun mapRenderLineToValidCoordinatesOrNull(line: MapRenderLine): List<Pair<Double, Double>>? {
    val filtered = line.coordinates.filter { isValidMapLibreGeographicLatLng(it.first, it.second) }
    return if (filtered.size >= 2) filtered else null
}

internal fun filterMapRenderPolygonForGeoJson(polygon: MapRenderPolygon): List<List<Pair<Double, Double>>>? {
    val rings = polygon.rings
        .map { ring -> ring.filter { isValidMapLibreGeographicLatLng(it.first, it.second) } }
        .filter { it.size >= 3 }
    return if (rings.isNotEmpty()) rings else null
}
