package com.geovault.places

import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface GeovaultApi {
    @GET("api/extensions/places/features/")
    fun getPlaces(@Query("sort") sort: String = "composite"): Call<FeatureCollection>

    @POST("api/extensions/places/features/{id}/navigate/")
    fun trackNavigation(@Path("id") id: Int): Call<Void>
}
