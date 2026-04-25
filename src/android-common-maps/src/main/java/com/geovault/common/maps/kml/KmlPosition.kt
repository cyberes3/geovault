package com.geovault.common.maps.kml

/**
 * One KML / WGS84 coordinate tuple (longitude, latitude, optional altitude in meters).
 */
data class KmlPosition(
    val longitude: Double,
    val latitude: Double,
    val altitudeMeters: Double? = null,
)
