package com.geovault.tracker

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.geovault.common.AppResetFlow
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.map.MapLibreInitializer
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TrackerApplication : Application(), GeovaultAuthManager.AuthFailureListener {

    companion object {
        private const val TAG = "GeoVaultTracker"
        private const val HOOK_STOP_SERVICES = "tracker_stop_services"
        private const val HOOK_CLEAR_TRACKER_STATE = "tracker_clear_tracker_state"
    }

    override fun onCreate() {
        super.onCreate()
        val redirectUri = "${BuildConfig.APPLICATION_ID}://oauth/callback"
        GeovaultAuthManager.init(
            this,
            redirectUri,
            GeovaultAuthManager.OAUTH_CLIENT_ID_TRACKER
        )
        AppResetFlow.registerHook(
            key = HOOK_STOP_SERVICES,
            phase = AppResetFlow.Phase.AFTER_TOKEN_CLEAR
        ) { hookContext ->
            hookContext.startService(
                Intent(hookContext, TrackingService::class.java).apply {
                    action = TrackingService.ACTION_STOP
                }
            )
            hookContext.startService(
                Intent(hookContext, LiveTrackStreamingService::class.java).apply {
                    action = LiveTrackStreamingService.ACTION_STOP
                }
            )
        }
        AppResetFlow.registerHook(
            key = HOOK_CLEAR_TRACKER_STATE,
            phase = AppResetFlow.Phase.AFTER_TOKEN_CLEAR
        ) { hookContext ->
            TrackerRepository.clearListCaches()
            TrackerRepository.clearSelectedTrackerCaches()
            SelectedTrackerPrefs.clearSelectedTracker(hookContext)
        }
        GeovaultAuthManager.setAuthFailureListener(this)
        MapLibreInitializer.init(applicationContext)
        createNotificationChannels()
    }

    override fun onAuthFailure(context: Context) {
        Log.w(TAG, "Unrecoverable auth failure detected. Resetting app.")
        AppResetFlow.execute(
            context = context,
            reason = AppResetFlow.Reason.AUTH_FAILURE,
            mainActivityClass = MainActivity::class.java
        )
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
        TrackingRecoveryCoordinator.createRecoveryChannel(applicationContext)
    }

}
