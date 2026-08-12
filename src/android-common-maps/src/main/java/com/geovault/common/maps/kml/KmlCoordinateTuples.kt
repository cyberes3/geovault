package com.geovault.common.maps.kml

/**
 * Parses KML coordinate list text (whitespace-separated lon,lat[,alt] tokens).
 */
object KmlCoordinateTuples {

    private val WHITESPACE = Regex("\\s+")

    /**
     * One `lon,lat[,alt]` tuple. Strips all whitespace before splitting, matching
     * backend `coord1()` so ` -71.06, 42.36, 0 ` still parses.
     */
    fun parsePosition(coordinateText: String?): KmlPosition? {
        if (coordinateText.isNullOrBlank()) return null
        val stripped = WHITESPACE.replace(coordinateText, "")
        val parts = stripped.split(',')
        val lon = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        val lat = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
        val alt = parts.getOrNull(2)?.toDoubleOrNull()
        return KmlPosition(longitude = lon, latitude = lat, altitudeMeters = alt)
    }

    /**
     * Space-separated `lon lat [alt]` (gx:Track `<gx:coord>`), matching backend `_gx_coords()`.
     */
    fun parseGxCoord(coordinateText: String?): KmlPosition? {
        if (coordinateText.isNullOrBlank()) return null
        val parts = coordinateText.trim().split(WHITESPACE)
        val lon = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        val lat = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
        val alt = parts.getOrNull(2)?.toDoubleOrNull()
        return KmlPosition(longitude = lon, latitude = lat, altitudeMeters = alt)
    }

    /**
     * Close a polygon ring by repeating the first vertex when it is not already closed,
     * matching backend `fix_ring()`.
     */
    fun closeRing(ring: List<KmlPosition>): List<KmlPosition> {
        if (ring.isEmpty()) return ring
        val first = ring.first()
        val last = ring.last()
        return if (first == last) ring else ring + first
    }

    /**
     * Ordered positions; invalid tokens are skipped.
     */
    fun parsePositions(coordinateText: String?): List<KmlPosition> {
        if (coordinateText.isNullOrBlank()) return emptyList()
        return coordinateText
            .trim()
            .split(WHITESPACE)
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
