package com.geovault.common.maps.core

import com.geovault.common.maps.model.TileSourceResponse
import retrofit2.Call
import retrofit2.http.GET

internal interface MapApi {
    @GET("/api/tiles/sources/")
    fun getTileSources(): Call<TileSourceResponse>
}
