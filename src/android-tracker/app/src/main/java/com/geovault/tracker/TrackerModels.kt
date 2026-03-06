package com.geovault.tracker

import android.os.Parcelable
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

@Parcelize
data class Tracker(
    val id: String,
    val name: String,
    val color: String?,
    val geometry: GeoJsonLineString? = null,
    @IgnoredOnParcel val point_params: List<Map<String, Any?>>? = null,
    val tracker_secret: String? = null,
    val created_at: String? = null,
    val updated_at: String? = null
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

data class TrackerUpdateRequest(
    val name: String? = null,
    val color: String? = null
)

/** Response from GET trackers/<id>/coordinates/ — latest 100 coordinates + point_params. */
data class TrackerCoordinatesResponse(
    val coordinates: List<List<Double>> = emptyList(),
    val point_params: List<Map<String, Any?>>? = null
)
