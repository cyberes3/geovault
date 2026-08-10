package com.geovault.places.data

import com.geovault.places.model.Feature

/**
 * Places API create/update body. Only fields allowed by server PlaceProperties (extra=forbid).
 */
data class PlaceWriteBody(
    val type: String = "Feature",
    val geometry: PlaceWriteGeometry,
    val properties: PlaceWriteProperties,
) {
    companion object {
        fun fromFeature(feature: Feature): PlaceWriteBody {
            val coords = feature.geometry.coordinates
            require(coords.size >= 2) { "Point coordinates require lon and lat" }
            val name = feature.properties.name.orEmpty().trim()
            require(name.isNotEmpty()) { "Place name is required" }
            val description = feature.properties.description
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            val address = feature.properties.address
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            return PlaceWriteBody(
                geometry = PlaceWriteGeometry(
                    coordinates = listOf(coords[0], coords[1]),
                ),
                properties = PlaceWriteProperties(
                    name = name,
                    description = description,
                    address = address,
                ),
            )
        }
    }
}

data class PlaceWriteGeometry(
    val type: String = "Point",
    val coordinates: List<Double>,
)

data class PlaceWriteProperties(
    val name: String,
    val description: String? = null,
    val address: String? = null,
)
