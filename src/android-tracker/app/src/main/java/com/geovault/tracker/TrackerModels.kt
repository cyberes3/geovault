package com.geovault.tracker

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Tracker(
    val id: String,
    val name: String,
    val color: String?,
    val geometry: GeoJsonLineString? = null
) : Parcelable

@Parcelize
data class GeoJsonLineString(
    val type: String,
    val coordinates: List<List<Double>>
) : Parcelable

data class TrackerCreateRequest(
    val name: String,
    val color: String? = null
)
