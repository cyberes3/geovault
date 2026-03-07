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

class TrackerApplication : Application(), GeovaultAuthManager.AuthFailureListener {

    companion object {
        private const val TAG = "GeoVaultTracker"

        /** Call from app start or after login to prefetch trackers and selected tracker in background. */
        fun prefetchIfNeeded(context: Context) {
            if (!GeovaultAuthManager.isLoggedIn(context)) return
            TrackerRepository.getTrackers(context, forceRefresh = true) {
                val prefs = context.getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
                val trackerId = prefs.getString("selected_tracker_id", null)
                if (!trackerId.isNullOrBlank()) {
                    TrackerRepository.getTracker(context, trackerId) { }
                }
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
        GeovaultAuthManager.setAuthFailureListener(this)
        prefetchIfNeeded(applicationContext)
        setMapLibreHttpClient()
        MapStyleCache.preloadMapTilerStyles(applicationContext)
        createNotificationChannels()
    }

    override fun onAuthFailure(context: Context) {
        Log.w(TAG, "Unrecoverable auth failure detected. Resetting app.")
        
        // 1. Clear tokens
        GeovaultAuthManager.clearTokens(context)
        
        // 2. Stop services
        context.startService(Intent(context, TrackingService::class.java).apply { action = TrackingService.ACTION_STOP })
        context.startService(Intent(context, LiveTrackStreamingService::class.java).apply { action = LiveTrackStreamingService.ACTION_STOP })
        
        // 3. Clear repository cache
        TrackerRepository.clearCache()
        TrackerRepository.clearCurrentTrackerCache()
        
        // 4. Return to login/guest screen
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        context.startActivity(intent)
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Add channel for Location Tracking Service (GPS)
        // IMPORTANCE_LOW (not MIN): on Android P+ this compacts the notification to a single line.
        // IMPORTANCE_MIN with a foreground service causes the system to show an extra high-priority
        // "app running in background" notification.
        val trackerChannel = NotificationChannel(
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
        manager.createNotificationChannel(trackerChannel)

        // Add channel for Live Track Streaming (WebSocket)
        val streamingChannel = NotificationChannel(
            "live_track_streaming",
            "Live Track Streaming",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent notification for live tracker streaming"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            enableLights(false)
            setLockscreenVisibility(Notification.VISIBILITY_SECRET)
            setBypassDnd(false)
        }
        manager.createNotificationChannel(streamingChannel)
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
    }
}
