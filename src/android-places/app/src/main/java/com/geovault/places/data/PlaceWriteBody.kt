package com.geovault.places.data

import com.geovault.places.model.Place

/**
 * Places API create/update body. Only fields allowed by server PlaceProperties (extra=forbid).
 */
data class PlaceWriteBody(
    val type: String = "Feature",
    val geometry: PlaceWriteGeometry,
    val properties: PlaceWriteProperties,
) {
    companion object {
        fun fromPlace(place: Place): PlaceWriteBody {
            val name = place.content.name.trim()
            require(name.isNotEmpty()) { "Place name is required" }
            val location = place.content.location
            val description = place.content.description.trim().takeIf { it.isNotEmpty() }
            val address = place.content.address?.trim()?.takeIf { it.isNotEmpty() }
            return PlaceWriteBody(
                geometry = PlaceWriteGeometry(
                    coordinates = listOf(location.longitude, location.latitude),
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
