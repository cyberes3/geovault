package com.geovault.tracker

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.map.MapStyleCache
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Route
import java.util.concurrent.TimeUnit

class TrackerApplication : Application() {

    companion object {
        private const val TAG = "GeoVaultTracker"

        /** Call from app start or after login to prefetch trackers and selected tracker in background. */
        fun prefetchIfNeeded(context: Context) {
            if (!GeovaultAuthManager.isLoggedIn(context)) return
            TrackerRepository.getTrackers(context, forceRefresh = true) { }
            val prefs = context.getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
            val trackerId = prefs.getString("selected_tracker_id", null)
            if (!trackerId.isNullOrBlank()) {
                TrackerRepository.getTracker(context, trackerId) { }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(applicationContext)
        val redirectUri = "${BuildConfig.APPLICATION_ID}://oauth/callback"
        GeovaultAuthManager.init(
            this,
            redirectUri,
            GeovaultAuthManager.OAUTH_CLIENT_ID_TRACKER
        )
        GeovaultAuthManager.fetchUserStatus(this)
        prefetchIfNeeded(applicationContext)
        setMapLibreHttpClient()
        MapStyleCache.preloadMapTilerStyles(applicationContext)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        // Delete old channel so Samsung (and others) get a fresh channel with correct importance.
        // Channel importance is immutable after first creation; Samsung may have created it with different defaults.
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.deleteNotificationChannel("tracker_service")
        }
        // IMPORTANCE_LOW (not MIN): on Android P+ this compacts the notification to a single line.
        // IMPORTANCE_MIN with a foreground service causes the system to show an extra high-priority
        // "app running in background" notification.
        val channel = NotificationChannel(
            TrackingService.CHANNEL_ID,
            "Location Tracking Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent notification for background location tracking"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            enableLights(false)
            setLockscreenVisibility(Notification.VISIBILITY_SECRET)
            setBypassDnd(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun isMapTilerHost(host: String?): Boolean =
        host != null && (host == "api.maptiler.com" || host.endsWith(".maptiler.com"))

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
                val token = GeovaultAuthManager.getAccessToken(appContext)
                val newRequest = if (!token.isNullOrBlank()) {
                    request.newBuilder().header("Authorization", "Bearer $token").build()
                } else {
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
    }
}
