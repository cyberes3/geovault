package com.geovault.tracker

import android.os.Parcelable
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import com.google.gson.JsonObject
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    val subscribed_at: Long? = null,
    val updated_at: Long? = null,
    val is_owner: Boolean? = null,
    val visibility: String? = null,
    val share_params_with_recipients: Boolean? = null,
    val share_params_with_world: Boolean? = null,
    val owner_email: String? = null,
    val subscriber_count: Int? = null,
    val world_share_id: String? = null,
    val world_share_url: String? = null,
    val shared_with_emails: List<String>? = null
) : Parcelable {

    fun isOwner(): Boolean = is_owner == true
}

@Parcelize
data class GeoJsonLineString(
    val type: String,
    val coordinates: List<List<Double>>
) : Parcelable

@Serializable
data class TrackerCreateRequest(
    val name: String,
    val color: String? = null
)

/** Request body for POST trackers/<id>/settings/. Matches backend TrackSettingsRequest. */
@Serializable
data class TrackerSettingsRequest(
    val name: String? = null,
    val color: String? = null,
    @SerialName("recent_data_window")
    val recent_data_window: String? = null,
    val visibility: String? = null,
    @SerialName("share_params_with_recipients")
    val share_params_with_recipients: Boolean? = null,
    @SerialName("share_params_with_world")
    val share_params_with_world: Boolean? = null,
    @SerialName("shared_with_emails")
    val shared_with_emails: List<String>? = null,
    @SerialName("world_share_enabled")
    val world_share_enabled: Boolean? = null,
    val hidden: Boolean? = null,
    @SerialName("allow_group_reshare")
    val allow_group_reshare: Boolean? = null
)

/** Response from GET trackers/<id>/coordinates/ — latest 100 coordinates + point_params. */
data class TrackerCoordinatesResponse(
    val coordinates: List<List<Double>> = emptyList(),
    val point_params: List<Map<String, Any?>>? = null
)

/** POST trackers/geometry/ request body. */
@Serializable
data class TrackerBulkGeometryRequest(
    @SerialName("tracker_ids")
    val tracker_ids: List<String> = emptyList()
)

/** GET trackers/available-to-add/ response. */
@Serializable
data class AvailableToAddResponse(
    val public: List<AvailableToAddItem> = emptyList(),
    val shared_with_me: List<AvailableToAddItem> = emptyList(),
    val shared_with_me_groups: List<AvailableToAddGroup> = emptyList(),
    val public_groups: List<AvailableToAddGroup> = emptyList()
)

@Serializable
data class AvailableToAddItem(
    val id: String,
    val name: String,
    val color: String? = null,
    val owner_email: String? = null
)

@Serializable
data class AvailableToAddGroup(
    val id: String,
    val name: String,
    val owner_email: String? = null,
    val track_ids: List<String> = emptyList()
)

/** GET trackers/<id>/subscribers/ response. */
@Serializable
data class SubscribersResponse(
    val subscribers: List<SubscriberItem> = emptyList()
)

@Serializable
data class SubscriberItem(
    val id: String,
    val email: String
)

/** GET / PATCH map-visibility/ request and response. */
@Serializable
data class MapVisibilityResponse(
    val hidden_track_ids: List<String> = emptyList(),
    val hidden_group_ids: List<String> = emptyList()
)

@Serializable
data class MapVisibilityRequest(
    @SerialName("hidden_track_ids")
    val hidden_track_ids: List<String>? = null,
    @SerialName("hidden_group_ids")
    val hidden_group_ids: List<String>? = null
)

@Serializable
data class HiddenItemsClearRequest(
    @SerialName("target_types")
    val target_types: List<String>? = null
)

/** Group payload from GET/POST/PATCH groups. */
@Serializable
@Parcelize
data class Group(
    val id: String,
    val name: String,
    val hidden: Boolean? = null,
    val visibility: String? = null,
    val shared_with_emails: List<String>? = null,
    val world_share_id: String? = null,
    val world_share_url: String? = null,
    val created_at: Long? = null,
    val updated_at: Long? = null,
    val is_owner: Boolean? = null,
    val is_accepted: Boolean? = null,
    val owner_email: String? = null,
    val track_ids: List<String>? = null
) : Parcelable {

    fun isOwner(): Boolean = is_owner == true
}

@Serializable
data class GroupCreateRequest(val name: String)

@Serializable
data class GroupPatchRequest(
    val name: String? = null,
    val hidden: Boolean? = null,
    val visibility: String? = null,
    @SerialName("shared_with_emails")
    val shared_with_emails: List<String>? = null,
    @SerialName("world_share_enabled")
    val world_share_enabled: Boolean? = null,
    @SerialName("add_track_ids")
    val add_track_ids: List<String>? = null,
    @SerialName("remove_track_ids")
    val remove_track_ids: List<String>? = null
)

@Serializable
data class GroupAddTrackRequest(@SerialName("track_id") val track_id: String)

data class TrackerAddToGroupCandidate(
    val tracker: Tracker,
    val canAdd: Boolean,
    val reason: String? = null
)

/** GET /api/users/ — list users for share recipient picker. */
@Serializable
data class UsersResponse(val users: List<UserItem> = emptyList())

@Serializable
data class UserItem(val id: Int, val email: String)

@Serializable
data class GeoJsonLineStringDto(
    val type: String,
    val coordinates: List<List<Double>>
)

data class TrackerDto(
    val id: String,
    val name: String,
    val color: String? = null,
    val settings: JsonObject? = null,
    val geometry: GeoJsonLineStringDto? = null,
    @SerialName("point_params") val point_params: List<JsonObject>? = null,
    @SerialName("last_point") val last_point: List<Double>? = null,
    val bbox: List<Double>? = null,
    @SerialName("tracker_secret") val tracker_secret: String? = null,
    @SerialName("created_at") val created_at: Long? = null,
    @SerialName("subscribed_at") val subscribed_at: Long? = null,
    @SerialName("updated_at") val updated_at: Long? = null,
    @SerialName("is_owner") val is_owner: Boolean? = null,
    val visibility: String? = null,
    @SerialName("share_params_with_recipients") val share_params_with_recipients: Boolean? = null,
    @SerialName("share_params_with_world") val share_params_with_world: Boolean? = null,
    @SerialName("owner_email") val owner_email: String? = null,
    @SerialName("subscriber_count") val subscriber_count: Int? = null,
    @SerialName("world_share_id") val world_share_id: String? = null,
    @SerialName("world_share_url") val world_share_url: String? = null,
    @SerialName("shared_with_emails") val shared_with_emails: List<String>? = null
)

data class TrackerCoordinatesResponseDto(
    val coordinates: List<List<Double>> = emptyList(),
    @SerialName("point_params") val point_params: List<JsonObject>? = null
)
