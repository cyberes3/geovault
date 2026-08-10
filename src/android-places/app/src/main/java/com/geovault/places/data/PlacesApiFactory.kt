package com.geovault.places.data

import android.content.Context
import com.geovault.common.RetrofitClient

object PlacesApiFactory {
    private val cache = RetrofitClient.CachedApiHolder<PlacesApi>()

    fun create(context: Context, baseUrl: String): PlacesApi {
        return RetrofitClient.createCachedApiOmitNulls(
            context = context,
            baseUrl = baseUrl,
            apiClass = PlacesApi::class.java,
            cache = cache,
        )
    }

    fun clearCache() {
        cache.clear()
    }
}
