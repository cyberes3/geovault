package com.geovault.common.map

import retrofit2.Call
import retrofit2.http.GET

interface MapApi {
    @GET("/api/tiles/sources/")
    fun getTileSources(): Call<TileSourceResponse>
}
