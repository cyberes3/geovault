package com.geovault.common.maps.geocoding

/**
 * One feature from `GET api/geocoding/search/` (`data.features[]`).
 *
 * [coordinates] follows GeoJSON for points: `[longitude, latitude]`.
 */
data class GeocodeSearchResult(
    val coordinates: List<Double>?,
    val place_name: String?,
    val text: String?,
)

/**
 * Returns `(longitude, latitude)` when [coordinates] has at least two values, else null.
 */
fun GeocodeSearchResult.longitudeLatitudeOrNull(): Pair<Double, Double>? {
    val c = coordinates ?: return null
    if (c.size < 2) return null
    val lon = c[0]
    val lat = c[1]
    return lon to lat
}
