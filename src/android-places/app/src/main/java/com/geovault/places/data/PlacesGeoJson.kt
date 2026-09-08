package com.geovault.places.data

import com.geovault.common.geo.LonLat
import com.geovault.places.model.Place
import com.geovault.places.model.PlaceContent
import com.geovault.places.model.PlaceKey

data class PlacesGeoJsonFeatureCollection(
    val type: String = "FeatureCollection",
    val features: List<PlacesGeoJsonFeature> = emptyList(),
)

data class PlacesGeoJsonFeature(
    val type: String = "Feature",
    val geometry: PlacesGeoJsonGeometry,
    val properties: PlacesGeoJsonProperties,
)

data class PlacesGeoJsonGeometry(
    val type: String = "Point",
    val coordinates: List<Double>,
)

data class PlacesGeoJsonProperties(
    val database_id: Int? = null,
    val name: String? = null,
    val description: String? = null,
    val created_at: String? = null,
    val address: String? = null,
)

fun PlacesGeoJsonFeature.toPlaceOrNull(): Place? {
    val location = LonLat.fromGeoJsonCoordinates(geometry.coordinates) ?: return null
    val name = properties.name?.trim().orEmpty()
    if (name.isEmpty()) return null
    val serverId = properties.database_id
    val key = if (serverId != null) PlaceKey.server(serverId) else PlaceKey.local()
    return Place(
        key = key,
        serverId = serverId,
        content = PlaceContent(
            name = name,
            description = properties.description?.trim().orEmpty(),
            location = location,
            address = properties.address?.trim()?.takeIf { it.isNotEmpty() },
        ),
        createdAt = properties.created_at,
        pending = null,
    )
}
