package com.geovault.places.data

import com.geovault.places.model.AddressSearchResponse
import com.geovault.places.model.Feature
import com.geovault.places.model.FeatureCollection
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
    fun getPlaces(@Query("sort") sort: String = "composite"): Call<FeatureCollection>

    @GET("api/extensions/places/features/{id}/")
    fun getPlace(@Path("id") id: Int): Call<Feature>

    @POST("api/extensions/places/features/")
    fun createPlace(@Body feature: Feature): Call<Feature>

    @PUT("api/extensions/places/features/{id}/")
    fun updatePlace(@Path("id") id: Int, @Body feature: Feature): Call<Feature>

    @DELETE("api/extensions/places/features/{id}/")
    fun deletePlace(@Path("id") id: Int): Call<Void>

    @POST("api/extensions/places/features/{id}/navigate/")
    fun trackNavigation(@Path("id") id: Int): Call<Void>

    @GET("api/geocoding/search/")
    fun geocodingSearch(@Query("q") query: String): Call<AddressSearchResponse>
}
