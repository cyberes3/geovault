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

data class TrackerCreateRequest(
    val name: String,
    val color: String? = null
)

/** Request body for POST trackers/<id>/settings/. Matches backend TrackSettingsRequest. */
data class TrackerSettingsRequest(
    val name: String? = null,
    val color: String? = null,
    val recent_data_window: String? = null,
    val visibility: String? = null,
    val share_params_with_recipients: Boolean? = null,
    val share_params_with_world: Boolean? = null,
    val shared_with_emails: List<String>? = null,
    val world_share_enabled: Boolean? = null,
    val hidden_in_list: Boolean? = null,
    val allow_group_reshare: Boolean? = null
)

/** Response from GET trackers/<id>/coordinates/ — latest 100 coordinates + point_params. */
data class TrackerCoordinatesResponse(
    val coordinates: List<List<Double>> = emptyList(),
    val point_params: List<Map<String, Any?>>? = null
)

/** POST trackers/geometry/ request body. */
data class TrackerBulkGeometryRequest(
    val tracker_ids: List<String> = emptyList(),
    val all_data: Boolean = false
)

/** GET trackers/available-to-add/ response. */
data class AvailableToAddResponse(
    val public: List<AvailableToAddItem> = emptyList(),
    val shared_with_me: List<AvailableToAddItem> = emptyList(),
    val shared_with_me_groups: List<AvailableToAddGroup> = emptyList(),
    val public_groups: List<AvailableToAddGroup> = emptyList()
)

data class AvailableToAddItem(
    val id: String,
    val name: String,
    val color: String? = null,
    val owner_email: String? = null
)

data class AvailableToAddGroup(
    val id: String,
    val name: String,
    val owner_email: String? = null,
    val track_ids: List<String> = emptyList()
)

/** GET trackers/<id>/subscribers/ response. */
data class SubscribersResponse(
    val subscribers: List<SubscriberItem> = emptyList()
)

data class SubscriberItem(
    val id: String,
    val email: String
)

/** GET / PATCH map-visibility/ request and response. */
data class MapVisibilityResponse(
    val hidden_track_ids: List<String> = emptyList(),
    val hidden_group_ids: List<String> = emptyList()
)

data class MapVisibilityRequest(
    val hidden_track_ids: List<String>? = null,
    val hidden_group_ids: List<String>? = null
)

/** Group payload from GET/POST/PATCH groups. */
@Parcelize
data class Group(
    val id: String,
    val name: String,
    val hidden_in_list: Boolean? = null,
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
) : Parcelable

data class GroupCreateRequest(val name: String)

data class GroupPatchRequest(
    val name: String? = null,
    val hidden_in_list: Boolean? = null,
    val visibility: String? = null,
    val shared_with_emails: List<String>? = null,
    val world_share_enabled: Boolean? = null
)

data class GroupAddTrackRequest(val track_id: String)

/** GET /api/users/ — list users for share recipient picker. */
data class UsersResponse(val users: List<UserItem> = emptyList())

data class UserItem(val id: Int, val email: String)
