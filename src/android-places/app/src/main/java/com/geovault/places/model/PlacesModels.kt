package com.geovault.places.model

import kotlinx.serialization.Serializable
import java.io.Serializable as JSerializable

@Serializable
data class FeatureCollection(
    val type: String = "FeatureCollection",
    val features: List<Feature> = emptyList(),
) : JSerializable

@Serializable
data class Feature(
    val type: String = "Feature",
    val geometry: Geometry,
    val properties: Properties,
) : JSerializable

@Serializable
data class Geometry(
    val type: String = "Point",
    val coordinates: List<Double>,
) : JSerializable

@Serializable
data class Properties(
    val database_id: Int? = null,
    val name: String? = null,
    val description: String? = null,
    val created_at: String? = null,
    val address: String? = null,
) : JSerializable

data class OfflineFeature(
    val feature: Feature,
    val original: Feature? = null,
) : JSerializable
