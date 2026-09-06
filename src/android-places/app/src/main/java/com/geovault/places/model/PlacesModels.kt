package com.geovault.places.model

import java.io.Serializable
import java.util.UUID

@kotlinx.serialization.Serializable
data class FeatureCollection(
    val type: String = "FeatureCollection",
    val features: List<Feature> = emptyList(),
) : Serializable

@kotlinx.serialization.Serializable
data class Feature(
    val type: String = "Feature",
    val geometry: Geometry,
    val properties: Properties,
) : Serializable

@kotlinx.serialization.Serializable
data class Geometry(
    val type: String = "Point",
    val coordinates: List<Double>,
) : Serializable

/** Server/read model properties. Never send this type as a write body. */
@kotlinx.serialization.Serializable
data class Properties(
    val database_id: Int? = null,
    val name: String? = null,
    val description: String? = null,
    val created_at: String? = null,
    val address: String? = null,
) : Serializable

/**
 * Pending offline create/edit identified solely by [clientLocalId].
 * Index-based queue APIs are not used.
 */
@kotlinx.serialization.Serializable
data class OfflineFeature(
    val clientLocalId: String = "",
    val feature: Feature,
    val original: Feature? = null,
) : Serializable {
    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}
