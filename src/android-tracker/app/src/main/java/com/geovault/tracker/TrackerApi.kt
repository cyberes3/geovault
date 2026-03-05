package com.geovault.tracker

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface TrackerApi {
    @GET("/api/extensions/live-track/trackers/")
    fun getTrackers(): Call<List<Tracker>>

    @GET("/api/extensions/live-track/trackers/{id}/")
    fun getTracker(@Path("id") id: String): Call<Tracker>

    @POST("/api/extensions/live-track/trackers/")
    fun createTracker(@Body request: TrackerCreateRequest): Call<Tracker>
}
