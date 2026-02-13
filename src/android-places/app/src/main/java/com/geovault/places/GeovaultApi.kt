package com.geovault.places

import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface GeovaultApi {
    @GET("api/extensions/places/features/")
    fun getPlaces(@Query("sort") sort: String = "composite"): Call<FeatureCollection>

    @GET("api/extensions/places/features/{id}/")
    fun getPlace(@Path("id") id: Int): Call<Feature>

    @POST("api/extensions/places/features/")
    fun createPlace(@retrofit2.http.Body feature: Feature): Call<Feature>

    @retrofit2.http.PUT("api/extensions/places/features/{id}/")
    fun updatePlace(@Path("id") id: Int, @retrofit2.http.Body feature: Feature): Call<Feature>

    @POST("api/extensions/places/features/{id}/navigate/")
    fun trackNavigation(@Path("id") id: Int): Call<Void>

    @retrofit2.http.DELETE("api/extensions/places/features/{id}/")
    fun deletePlace(@Path("id") id: Int): Call<Void>
}
