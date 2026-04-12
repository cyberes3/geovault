package com.geovault.common.maps.core

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

object MapLibreInitializer {
    private var initialized = false
    private const val TAG = "MapLibreInitializer"

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        MapLibre.getInstance(context.applicationContext)
        setupHttpClient(context.applicationContext)
        MapStyleCache.preloadMapTilerStyles(context.applicationContext)
        initialized = true
    }

    private fun isMapTilerHost(host: String?): Boolean {
        return host != null && (host == "api.maptiler.com" || host.endsWith(".maptiler.com"))
    }

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
            if (!isOurServer) return@Interceptor chain.proceed(request)
            val token = GeovaultAuthManager.getAccessToken(context)
            val newRequest = if (!token.isNullOrBlank()) {
                request.newBuilder().header("Authorization", "Bearer $token").build()
            } else {
                request
            }
            chain.proceed(newRequest)
        }
        val authFailureInterceptor = Interceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.code == 403) {
                GeovaultAuthManager.handleAuthFailure(context)
            } else if (response.code == 401 && response.request.header("X-Geovault-Retry") != null) {
                GeovaultAuthManager.handleAuthFailure(context)
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
        Log.d(TAG, "MapLibre HTTP client configured")
    }
}
