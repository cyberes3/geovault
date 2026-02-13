package com.geovault.places

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue

@Parcelize
data class FeatureCollection(
    val type: String,
    val features: List<Feature>
) : Parcelable

@Parcelize
data class Feature(
    val type: String,
    val geometry: Geometry,
    val properties: Properties
) : Parcelable

@Parcelize
data class Geometry(
    val type: String,
    val coordinates: List<Double>
) : Parcelable

@Parcelize
data class Properties(
    val database_id: Int? = null,
    val name: String?,
    val description: String?,
    val created_at: String?
) : Parcelable
