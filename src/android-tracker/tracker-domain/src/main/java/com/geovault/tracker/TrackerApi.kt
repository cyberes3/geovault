package com.geovault.tracker

import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

/** Request body for POST tracker-check: validate tracker ID (password optional). */
data class TrackerCheckRequest(val tracker_id: String, val password: String? = null)

/** Response from tracker-check: valid and optional name when valid. */
data class TrackerCheckResponse(val valid: Boolean, val name: String? = null)

interface TrackerApi {
    @GET("/api/extensions/live-track/trackers/")
    fun getTrackers(): Call<List<Tracker>>

    @GET("/api/extensions/live-track/trackers/available-to-add/")
    fun getAvailableToAdd(): Call<AvailableToAddResponse>

    @GET("/api/extensions/live-track/trackers/{id}/")
    fun getTracker(@Path("id") id: String): Call<Tracker>

    @GET("/api/extensions/live-track/trackers/{id}/geometry/")
    fun getTrackerGeometry(@Path("id") id: String): Call<Tracker>

    @POST("/api/extensions/live-track/trackers/geometry/")
    fun getTrackersGeometry(@Body request: TrackerBulkGeometryRequest): Call<List<Tracker>>

    @GET("/api/extensions/live-track/trackers/{id}/coordinates/")
    fun getTrackerCoordinates(@Path("id") id: String): Call<TrackerCoordinatesResponse>

    @POST("/api/extensions/live-track/trackers/")
    fun createTracker(@Body request: TrackerCreateRequest): Call<Tracker>

    @POST("/api/extensions/live-track/trackers/{id}/settings/")
    fun postTrackerSettings(@Path("id") id: String, @Body request: TrackerSettingsRequest): Call<Tracker>

    @DELETE("/api/extensions/live-track/trackers/{id}/")
    fun deleteTracker(@Path("id") id: String): Call<ResponseBody>

    @POST("/api/extensions/live-track/trackers/{id}/clear-history/")
    fun clearTrackerHistory(@Path("id") id: String): Call<ResponseBody>

    @POST("/api/extensions/live-track/trackers/{id}/subscribe/")
    fun subscribeTracker(@Path("id") id: String): Call<Tracker>

    @DELETE("/api/extensions/live-track/trackers/{id}/subscribe/")
    fun unsubscribeTracker(@Path("id") id: String): Call<ResponseBody>

    @DELETE("/api/extensions/live-track/trackers/{id}/share-with-me/")
    fun leaveShareWithMe(@Path("id") id: String): Call<ResponseBody>

    @GET("/api/extensions/live-track/trackers/{id}/subscribers/")
    fun getSubscribers(@Path("id") id: String): Call<SubscribersResponse>

    @GET("/api/extensions/live-track/trackers/{id}/kml/")
    fun getTrackerKml(@Path("id") id: String): Call<ResponseBody>

    @GET("/api/extensions/live-track/map-visibility/")
    fun getMapVisibility(): Call<MapVisibilityResponse>

    @PATCH("/api/extensions/live-track/map-visibility/")
    fun patchMapVisibility(@Body request: MapVisibilityRequest): Call<MapVisibilityResponse>

    @GET("/api/extensions/live-track/groups/")
    fun getGroups(): Call<List<Group>>

    @POST("/api/extensions/live-track/groups/")
    fun createGroup(@Body request: GroupCreateRequest): Call<Group>

    @GET("/api/extensions/live-track/groups/{id}/")
    fun getGroup(@Path("id") id: String): Call<Group>

    @PATCH("/api/extensions/live-track/groups/{id}/")
    fun patchGroup(@Path("id") id: String, @Body request: GroupPatchRequest): Call<Group>

    @DELETE("/api/extensions/live-track/groups/{id}/")
    fun deleteGroup(@Path("id") id: String): Call<ResponseBody>

    @POST("/api/extensions/live-track/groups/{id}/tracks/")
    fun addGroupTrack(@Path("id") id: String, @Body request: GroupAddTrackRequest): Call<Group>

    @DELETE("/api/extensions/live-track/groups/{id}/tracks/{track_id}/")
    fun removeGroupTrack(@Path("id") id: String, @Path("track_id") trackId: String): Call<ResponseBody>

    @POST("/api/extensions/live-track/groups/{id}/accept-share/")
    fun acceptGroupShare(@Path("id") id: String): Call<Group>

    @DELETE("/api/extensions/live-track/groups/{id}/leave/")
    fun leaveGroup(@Path("id") id: String): Call<ResponseBody>

    @GET("/api/users/")
    fun getUsers(): Call<UsersResponse>

    @POST("/api/extensions/live-track/tracker-check/")
    fun checkTracker(@Body request: TrackerCheckRequest): Call<TrackerCheckResponse>
}
