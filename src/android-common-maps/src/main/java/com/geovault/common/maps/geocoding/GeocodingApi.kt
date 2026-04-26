package com.geovault.common.maps.geocoding

import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Query

internal interface GeocodingApi {
    @GET("api/geocoding/search/")
    fun geocodingSearch(@Query("q") query: String): Call<GeocodeSearchResponse>
}
