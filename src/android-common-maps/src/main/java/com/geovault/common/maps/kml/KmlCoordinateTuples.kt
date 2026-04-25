package com.geovault.common.maps.kml

/**
 * Parses KML coordinate list text (whitespace-separated lon,lat[,alt] tokens).
 */
object KmlCoordinateTuples {

    /**
     * Ordered positions; invalid tokens are skipped.
     */
    fun parsePositions(coordinateText: String?): List<KmlPosition> {
        if (coordinateText.isNullOrBlank()) return emptyList()
        return coordinateText
            .trim()
            .split(Regex("\\s+"))
            .mapNotNull { token ->
                if (token.isBlank()) return@mapNotNull null
                val parts = token.split(',')
                val lon = parts.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
                val lat = parts.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
                val alt = parts.getOrNull(2)?.toDoubleOrNull()
                KmlPosition(longitude = lon, latitude = lat, altitudeMeters = alt)
            }
    }
}
