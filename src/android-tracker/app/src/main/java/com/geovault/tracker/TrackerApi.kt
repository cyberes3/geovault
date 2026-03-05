package com.geovault.tracker

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/** Request body for POST tracker-check: validate tracker ID (password optional). */
data class TrackerCheckRequest(val tracker_id: String, val password: String? = null)

/** Response from tracker-check: valid and optional name when valid. */
data class TrackerCheckResponse(val valid: Boolean, val name: String? = null)

interface TrackerApi {
    @GET("/api/extensions/live-track/trackers/")
    fun getTrackers(): Call<List<Tracker>>

    @GET("/api/extensions/live-track/trackers/{id}/")
    fun getTracker(@Path("id") id: String): Call<Tracker>

    @POST("/api/extensions/live-track/trackers/")
    fun createTracker(@Body request: TrackerCreateRequest): Call<Tracker>

    @POST("/api/extensions/live-track/tracker-check/")
    fun checkTracker(@Body request: TrackerCheckRequest): Call<TrackerCheckResponse>
}
