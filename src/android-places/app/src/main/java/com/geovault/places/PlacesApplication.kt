package com.geovault.places

import android.app.Application
import android.util.Log
import com.geovault.common.GeovaultAuthManager
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Route
import java.util.concurrent.TimeUnit

class PlacesApplication : Application() {

    companion object {
        private const val TAG = "GeoVaultMap"
    }
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(applicationContext)
        GeovaultAuthManager.init(this, "com.geovault.places://oauth/callback", GeovaultAuthManager.OAUTH_CLIENT_ID_PLACES)
        GeovaultAuthManager.fetchUserStatus(this)
        setMapLibreHttpClient()
        MapStyleCache.preloadMapTilerStyles(applicationContext)
    }

    /** MapTiler host names that require Origin/Referer to match the GeoVault URL for 403 avoidance. */
    private fun isMapTilerHost(host: String?): Boolean =
        host != null && (host == "api.maptiler.com" || host.endsWith(".maptiler.com"))

    /**
     * Use a single OkHttp client for all MapLibre requests (style, tiles, glyphs, sprite).
     * Adds Bearer token only for requests to our server; fakes Origin/Referer for MapTiler so
     * the configured GeoVault URL is used as the origin (MapTiler domain whitelist).
     */
    private fun setMapLibreHttpClient() {
        val appContext = applicationContext
        val originInterceptor = Interceptor { chain ->
            val request = chain.request()
            val host = request.url.host
            val serverUrl = GeovaultAuthManager.getServerUrl(appContext).trimEnd('/')
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
            val serverUrl = GeovaultAuthManager.getServerUrl(appContext).trimEnd('/')
            val request = chain.request()
            val urlString = request.url.toString()
            val isOurServer = serverUrl.isNotEmpty() && (urlString == serverUrl || urlString.startsWith("$serverUrl/"))
            if (isOurServer) {
                Log.d(TAG, "MapLibre request to our server: $urlString")
                val token = GeovaultAuthManager.getAccessToken(appContext)
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
        val tokenAuthenticator = Authenticator { _: Route?, response: okhttp3.Response ->
            if (response.priorResponse?.code == 401) return@Authenticator null
            val serverUrl = GeovaultAuthManager.getServerUrl(appContext).trimEnd('/')
            val urlString = response.request.url.toString()
            if (serverUrl.isEmpty() || !urlString.startsWith("$serverUrl/")) return@Authenticator null
            val newToken = try {
                GeovaultAuthManager.getValidAccessToken(appContext, forceRefreshForToken = null)
            } catch (_: Exception) {
                return@Authenticator null
            }
            if (newToken.isNullOrBlank()) return@Authenticator null
            response.request.newBuilder().header("Authorization", "Bearer $newToken").build()
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(originInterceptor)
            .addInterceptor(authInterceptor)
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
