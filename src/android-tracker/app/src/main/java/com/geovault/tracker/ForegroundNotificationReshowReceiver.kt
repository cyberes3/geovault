package com.geovault.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.geovault.tracker.runtime.RuntimeCommand
import com.geovault.tracker.runtime.RuntimeCommandType
import com.geovault.tracker.runtime.RuntimeTrigger
import com.geovault.tracker.runtime.TrackingRuntimeController

/**
 * NOTIF-FGS-ESCALATION: notification-dismiss intents arrive while the app may be in the
 * background. A bare `startService` will throw IllegalStateException on Android 8+ when no
 * caller is currently in the foreground. Mirror [MapStreamingServiceHelper.startStreaming]'s
 * escalation: try the cheap [Context.startService] path first, fall back to
 * [ContextCompat.startForegroundService] when the system rejects the background start. The
 * service then has the standard FGS deadline to call [android.app.Service.startForeground],
 * which it does inside `ACTION_RESHOW_FOREGROUND`.
 */
class ForegroundNotificationReshowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pkg = context.packageName
        when (intent?.action) {
            TrackingService.NOTIFICATION_DISMISSED_ACTION -> {
                TrackingRuntimeController.get(context.applicationContext).handle(
                    RuntimeCommand(
                        type = RuntimeCommandType.RESHOW_FOREGROUND,
                        trigger = RuntimeTrigger.RESHOW_FOREGROUND,
                        reason = "notification_dismissed"
                    )
                )
            }
            LiveTrackStreamingService.NOTIFICATION_DISMISSED_ACTION -> {
                val reshowIntent = Intent(context, LiveTrackStreamingService::class.java).apply {
                    action = LiveTrackStreamingService.ACTION_RESHOW_FOREGROUND
                    setPackage(pkg)
                }
                deliverWithFgsEscalation(context, reshowIntent)
            }
        }
    }

    private fun deliverWithFgsEscalation(context: Context, intent: Intent) {
        val app = context.applicationContext
        try {
            app.startService(intent)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "startService rejected for streaming reshow; escalating to FGS", e)
            runCatching { ContextCompat.startForegroundService(app, intent) }
                .exceptionOrNull()
                ?.let { Log.e(TAG, "FGS streaming reshow start also failed", it) }
        }
    }

    private companion object {
        const val TAG = "ReshowReceiver"
    }
}
