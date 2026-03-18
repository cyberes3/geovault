package com.geovault.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * When the user dismisses a foreground service notification, re-starts the service so it can
 * call [android.app.Service.startForeground] again.
 */
class ForegroundNotificationReshowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pkg = context.packageName
        when (intent?.action) {
            TrackingService.NOTIFICATION_DISMISSED_ACTION -> {
                context.startService(
                    Intent(context, TrackingService::class.java).apply {
                        action = TrackingService.ACTION_RESHOW_FOREGROUND
                        setPackage(pkg)
                    }
                )
            }
            LiveTrackStreamingService.NOTIFICATION_DISMISSED_ACTION -> {
                context.startService(
                    Intent(context, LiveTrackStreamingService::class.java).apply {
                        action = LiveTrackStreamingService.ACTION_RESHOW_FOREGROUND
                        setPackage(pkg)
                    }
                )
            }
        }
    }
}
