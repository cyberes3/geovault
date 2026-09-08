package com.geovault.places.data

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface PlacesApi {
    @GET("api/extensions/places/features/")
    fun getPlaces(@Query("sort") sort: String = "composite"): Call<PlacesGeoJsonFeatureCollection>

    @GET("api/extensions/places/features/{id}/")
    fun getPlace(@Path("id") id: Int): Call<PlacesGeoJsonFeature>

    @POST("api/extensions/places/features/")
    fun createPlace(@Body body: PlaceWriteBody): Call<PlacesGeoJsonFeature>

    @PUT("api/extensions/places/features/{id}/")
    fun updatePlace(@Path("id") id: Int, @Body body: PlaceWriteBody): Call<PlacesGeoJsonFeature>

    @DELETE("api/extensions/places/features/{id}/")
    fun deletePlace(@Path("id") id: Int): Call<Void>

    @POST("api/extensions/places/features/{id}/navigate/")
    fun trackNavigation(@Path("id") id: Int): Call<Void>
}
