package com.geovault.places

import android.app.Application
import android.content.Context
import android.util.Log
import android.content.ContentValues
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.map.*
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Route
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class PlacesApplication : Application(), GeovaultAuthManager.AuthFailureListener {

    val placesCache: PlacesCache by lazy { PlacesCache(applicationContext) }

    companion object {
        private const val TAG = "GeoVaultMap"
    }
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(applicationContext)
        GeovaultAuthManager.init(this, "com.geovault.places://oauth/callback", GeovaultAuthManager.OAUTH_CLIENT_ID_PLACES)
        GeovaultAuthManager.setAuthFailureListener(this)
        GeovaultAuthManager.fetchUserStatus(this)
        setMapLibreHttpClient()
        MapStyleCache.preloadMapTilerStyles(applicationContext)
    }

    override fun onAuthFailure(context: Context) {
        Log.w(TAG, "Unrecoverable auth failure detected. Resetting app.")
        
        // 1. Emergency Export of unsynced data
        performEmergencyExport(context)

        // 2. Clear tokens
        GeovaultAuthManager.clearTokens(context)
        
        // 3. Clear cache
        placesCache.clear()
        
        // 4. Clear app-specific prefs (matches AppResetHelper logic)
        context.getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE).edit()
            .remove("pending_navigation_ids")
            .apply()

        // 5. Return to login screen
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            // Signal to MainActivity that an export might have been saved
            putExtra(MainActivity.EXTRA_SHOW_EXPORT_SAVED_MESSAGE, true)
        }
        context.startActivity(intent)
    }

    private fun performEmergencyExport(context: Context) {
        val offlineList = placesCache.getOfflineFeatures()
        val cachedFeatures = placesCache.getCachedFeatures()
        val hasData = offlineList.isNotEmpty() || cachedFeatures.isNotEmpty()

        if (!hasData) return

        try {
            val sb = StringBuilder()
            val exportedAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
            sb.appendLine("GeoVault emergency export — $exportedAt")
            sb.appendLine()
            for (offline in offlineList) {
                sb.append(formatPlaceBlock(offline.feature, "offline"))
            }
            val cachedFiltered = cachedFeatures.filter { c ->
                offlineList.none { it.feature.properties.database_id == c.properties.database_id }
            }
            for (feature in cachedFiltered) {
                sb.append(formatPlaceBlock(feature, "cached"))
            }
            val txtBytes = sb.toString().toByteArray(Charsets.UTF_8)
            val filename = "geovault_emergency_export_${SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())}.txt"
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)?.let { uri ->
                context.contentResolver.openOutputStream(uri)?.use { it.write(txtBytes) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Emergency export failed", e)
        }
    }

    private fun formatPlaceBlock(feature: Feature, source: String): String {
        val p = feature.properties
        val coords = feature.geometry.coordinates
        val coordsLine = if (coords.size >= 2) "${coords[1]}, ${coords[0]}" else ""
        val addressLine = p.address?.takeIf { it.isNotBlank() } ?: ""
        val descLine = p.description?.takeIf { it.isNotBlank() } ?: ""
        val other = buildList {
            if (p.database_id != null) add("database_id: ${p.database_id}")
            add(source)
        }.joinToString(" | ")
        return buildString {
            appendLine(p.name?.takeIf { it.isNotBlank() } ?: "(unnamed)")
            appendLine(p.created_at ?: "")
            appendLine(coordsLine)
            appendLine(addressLine)
            appendLine(descLine)
            appendLine(other)
            appendLine()
        }
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
        val authFailureInterceptor = Interceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.code == 403) {
                GeovaultAuthManager.handleAuthFailure(appContext)
            } else if (response.code == 401) {
                val isRetry = response.request.header("X-Geovault-Retry") != null
                if (isRetry) {
                    GeovaultAuthManager.handleAuthFailure(appContext)
                }
            }
            response
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
