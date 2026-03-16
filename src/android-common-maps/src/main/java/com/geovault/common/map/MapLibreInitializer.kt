package com.geovault.common.map

import android.content.Context
import android.util.Log
import com.geovault.common.GeovaultAuthManager
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Route
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import java.util.concurrent.TimeUnit

/**
 * Central initialization for MapLibre used by all GeoVault apps.
 * Call [init] from Application.onCreate().
 */
object MapLibreInitializer {

    private const val TAG = "MapLibreInitializer"

    /**
     * Initialize MapLibre and set the HTTP client for style/tile requests.
     * Adds Bearer token for GeoVault server; Origin/Referer for MapTiler.
     * Also preloads MapTiler styles for faster layer switching.
     */
    fun init(context: Context) {
        MapLibre.getInstance(context)
        setupHttpClient(context)
        MapStyleCache.preloadMapTilerStyles(context)
    }

    private fun isMapTilerHost(host: String?): Boolean =
        host != null && (host == "api.maptiler.com" || host.endsWith(".maptiler.com"))

    private fun setupHttpClient(context: Context) {
        val originInterceptor = Interceptor { chain ->
            val request = chain.request()
            val host = request.url.host
            val serverUrl = GeovaultAuthManager.getServerUrl(context).trimEnd('/')
            val newRequest = if (isMapTilerHost(host) && serverUrl.isNotEmpty()) {
                request.newBuilder()
                    .header("Origin", serverUrl)
                    .header("Referer", "$serverUrl/")
                    .build()
            } else {
                request
            }
            chain.proceed(newRequest)
        }
        val authInterceptor = Interceptor { chain ->
            val serverUrl = GeovaultAuthManager.getServerUrl(context).trimEnd('/')
            val request = chain.request()
            val urlString = request.url.toString()
            val isOurServer = serverUrl.isNotEmpty() && (urlString == serverUrl || urlString.startsWith("$serverUrl/"))
            if (isOurServer) {
                Log.d(TAG, "MapLibre request to our server: $urlString")
                val token = GeovaultAuthManager.getAccessToken(context)
                val newRequest = if (!token.isNullOrBlank()) {
                    request.newBuilder().header("Authorization", "Bearer $token").build()
                } else {
                    Log.w(TAG, "MapLibre request to our server but no token")
                    request
                }
                chain.proceed(newRequest)
            } else {
                chain.proceed(request)
            }
        }
        val authFailureInterceptor = Interceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.code == 403) {
                GeovaultAuthManager.handleAuthFailure(context)
            } else if (response.code == 401) {
                val isRetry = response.request.header("X-Geovault-Retry") != null
                if (isRetry) {
                    GeovaultAuthManager.handleAuthFailure(context)
                }
            }
            response
        }
        val tokenAuthenticator = Authenticator { _: Route?, response: okhttp3.Response ->
            if (response.priorResponse?.code == 401) return@Authenticator null
            val serverUrl = GeovaultAuthManager.getServerUrl(context).trimEnd('/')
            val urlString = response.request.url.toString()
            if (serverUrl.isEmpty() || !urlString.startsWith("$serverUrl/")) return@Authenticator null
            val newToken = try {
                GeovaultAuthManager.getValidAccessToken(context, forceRefreshForToken = null)
            } catch (_: Exception) {
                return@Authenticator null
            }
            if (newToken.isNullOrBlank()) return@Authenticator null
            response.request.newBuilder()
                .header("Authorization", "Bearer $newToken")
                .header("X-Geovault-Retry", "true")
                .build()
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(originInterceptor)
            .addInterceptor(authInterceptor)
            .addInterceptor(authFailureInterceptor)
            .authenticator(tokenAuthenticator)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        HttpRequestUtil.setOkHttpClient(client)
        HttpRequestUtil.setLogEnabled(true)
        HttpRequestUtil.setPrintRequestUrlOnFailure(true)
        Log.d(TAG, "MapLibre HTTP client set (auth for server host only)")
    }
}
