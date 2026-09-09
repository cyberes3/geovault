package com.geovault.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.geovault.tracker.streaming.DefaultLiveStreamHostPort
import com.geovault.tracker.streaming.SharedPrefsLiveStreamPersistPort
import com.geovault.tracker.tracking.TrackingServiceIntents

/**
 * Notification-dismiss intents arrive while the app may be in the background. Tracking reshow
 * goes through the runtime engine; streaming reshow goes through [DefaultLiveStreamHostPort].
 */
class ForegroundNotificationReshowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            TrackingServiceIntents.NOTIFICATION_DISMISSED_ACTION -> {
                com.geovault.tracker.runtime.TrackerRuntimeEngine.get(context.applicationContext).handle(
                    com.geovault.tracker.runtime.TrackerRuntimeCommands.ReshowForeground(
                        reason = "notification_dismissed"
                    )
                )
            }
            LiveTrackStreamingService.NOTIFICATION_DISMISSED_ACTION -> {
                val persist = SharedPrefsLiveStreamPersistPort(context)
                DefaultLiveStreamHostPort(context, persist).reshow(context)
            }
        }
    }
}
