package com.geovault.tracker

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.map.MapLibreInitializer

class TrackerApplication : Application(), GeovaultAuthManager.AuthFailureListener {

    companion object {
        private const val TAG = "GeoVaultTracker"

        /** Call from app start or after login to prefetch trackers and selected tracker in background. */
        fun prefetchIfNeeded(context: Context) {
            if (!GeovaultAuthManager.isLoggedIn(context)) return
            TrackerRepository.getTrackers(context, forceRefresh = true) {
                val trackerId = SelectedTrackerPrefs.selectedTrackerId(context)
                if (trackerId.isNotBlank()) {
                    TrackerRepository.getTracker(context, trackerId) { }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val redirectUri = "${BuildConfig.APPLICATION_ID}://oauth/callback"
        GeovaultAuthManager.init(
            this,
            redirectUri,
            GeovaultAuthManager.OAUTH_CLIENT_ID_TRACKER
        )
        GeovaultAuthManager.setAuthFailureListener(this)
        MapLibreInitializer.init(applicationContext)
        prefetchIfNeeded(applicationContext)
        createNotificationChannels()
    }

    override fun onAuthFailure(context: Context) {
        Log.w(TAG, "Unrecoverable auth failure detected. Resetting app.")
        
        // 1. Clear tokens
        GeovaultAuthManager.clearTokens(context)
        
        // 2. Stop services
        context.startService(Intent(context, TrackingService::class.java).apply { action = TrackingService.ACTION_STOP })
        context.startService(Intent(context, LiveTrackStreamingService::class.java).apply { action = LiveTrackStreamingService.ACTION_STOP })
        
        // 3. Clear repository caches (list + selected-tracker)
        TrackerRepository.clearListCaches()
        TrackerRepository.clearSelectedTrackerCaches()
        
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

}
