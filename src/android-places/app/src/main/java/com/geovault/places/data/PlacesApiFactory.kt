package com.geovault.places.data

import com.geovault.common.net.GeoVaultHttp
import com.geovault.common.net.GeoVaultServerUrl

object PlacesApiFactory {
    private val cache = GeoVaultHttp.CachedApiHolder<PlacesApi>()

    fun create(baseUrl: String): PlacesApi {
        val parsed = GeoVaultServerUrl.parse(baseUrl)
            ?: error("Cannot create Places API without a valid server URL")
        return GeoVaultHttp.createCachedApi(parsed, PlacesApi::class.java, cache)
    }

    fun clearCache() {
        cache.clear()
    }
}
