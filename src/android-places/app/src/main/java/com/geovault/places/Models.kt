package com.geovault.places

import android.content.res.Configuration
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import android.content.Context
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import com.geovault.common.GeovaultAuthManager

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
    val created_at: String?,
    val address: String? = null
) : Parcelable

/** Backend returns { "data": { "query": "...", "features": [ { "coordinates": [lng, lat], "place_name": "...", "text": "..."? }, ... ] } } */
data class AddressSearchResponse(val data: GeocodingResponseData?)

data class GeocodingResponseData(
    val query: String? = null,
    val features: List<AddressSearchResult>? = null
)

data class AddressSearchResult(
    val coordinates: List<Double>?,
    val place_name: String?,
    val text: String?
)
