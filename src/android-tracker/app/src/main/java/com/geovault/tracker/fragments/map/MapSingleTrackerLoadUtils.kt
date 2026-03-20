package com.geovault.tracker.fragments.map

internal object MapSingleTrackerLoadUtils {
    fun sanitizeHistory(coords: MutableList<List<Double>>): List<List<Double>> {
        return coords
    }

    fun mergedCoordinates(
        geometryCoords: List<List<Double>>,
        responseCoords: List<List<Double>>
    ): List<List<Double>> {
        val normalizedGeometry = MapCoordinateUtils.normalizeRawCoordinates(geometryCoords)
        val normalizedResponse = MapCoordinateUtils.normalizeRawCoordinates(responseCoords)
        if (normalizedGeometry.isEmpty()) return sanitizeHistory(normalizedResponse)
        if (normalizedResponse.isEmpty()) return sanitizeHistory(normalizedGeometry)
        val base = if (normalizedGeometry.size >= normalizedResponse.size) {
            normalizedGeometry
        } else {
            normalizedResponse
        }
        val other = if (base === normalizedGeometry) normalizedResponse else normalizedGeometry
        MapCoordinateUtils.mergeNewerPointsInto(base, other)
        // Keep single-point histories so backend fallback-to-latest remains visible on map.
        return sanitizeHistory(base)
    }
}
