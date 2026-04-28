package com.geovault.common.maps.core

import android.content.Context
import com.geovault.common.GeovaultAuthManager
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds `Origin` and `Referer` headers to MapTiler requests so they pass the
 * domain allowlist on `api.maptiler.com`. The headers are resolved per
 * request from the calling app's stored GeoVault server URL via
 * [GeovaultAuthManager.getServerUrl], so a server URL change after init is
 * picked up without rebuilding the OkHttp client.
 *
 * Implemented as a class (rather than an inline `Interceptor { ... }`
 * lambda) so the same definition is reused by both the global MapLibre
 * OkHttp client and the [MapStyleCache] external-style client.
 */
internal class MapTilerOriginInterceptor(
    context: Context,
) : Interceptor {

    private val appContext = context.applicationContext

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!isMapTilerHost(request.url.host)) return chain.proceed(request)
        val serverUrl = GeovaultAuthManager.getServerUrl(appContext).trimEnd('/')
        if (serverUrl.isEmpty()) return chain.proceed(request)
        return chain.proceed(
            request.newBuilder()
                .header("Origin", serverUrl)
                .header("Referer", "$serverUrl/")
                .build(),
        )
    }

    private fun isMapTilerHost(host: String): Boolean =
        host == "api.maptiler.com" || host.endsWith(".maptiler.com")
}
