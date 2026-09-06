package com.geovault.common.geo

/**
 * Named WGS84 geographic point (latitude, then longitude).
 *
 * Prefer this over `Pair<Double, Double>` so call sites cannot mix axis order.
 */
data class Wgs84Point(
    val latitude: Double,
    val longitude: Double,
) {
    fun isValidGeographic(): Boolean = GeoCoordinates.isValidGeographic(latitude, longitude)

    fun asLonLat(): LonLat = LonLat(longitude = longitude, latitude = latitude)
}

/**
 * Longitude-first geographic pair (GeoJSON / MapLibre axis order) with named components.
 */
data class LonLat(
    val longitude: Double,
    val latitude: Double,
) {
    fun isValidGeographic(): Boolean = GeoCoordinates.isValidGeographic(latitude, longitude)

    fun asWgs84(): Wgs84Point = Wgs84Point(latitude = latitude, longitude = longitude)
}
