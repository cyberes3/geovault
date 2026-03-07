package com.geovault.tracker

import android.os.Parcelable
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

@Parcelize
data class Tracker(
    val id: String,
    val name: String,
    val color: String?,
    @IgnoredOnParcel val settings: Map<String, Any?>? = null,
    val geometry: GeoJsonLineString? = null,
    @IgnoredOnParcel val point_params: List<Map<String, Any?>>? = null,
    val last_point: List<Double>? = null,
    val bbox: List<Double>? = null,
    val tracker_secret: String? = null,
    val created_at: Long? = null,
    val updated_at: Long? = null
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

/** Request body for POST trackers/<id>/settings/. Updates name (column), color and recent_data_window (in settings). */
data class TrackerSettingsRequest(
    val name: String? = null,
    val color: String? = null,
    val recent_data_window: String? = null
)

/** Response from GET trackers/<id>/coordinates/ — latest 100 coordinates + point_params. */
data class TrackerCoordinatesResponse(
    val coordinates: List<List<Double>> = emptyList(),
    val point_params: List<Map<String, Any?>>? = null
)
