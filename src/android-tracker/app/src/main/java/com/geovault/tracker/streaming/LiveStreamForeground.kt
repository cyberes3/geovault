package com.geovault.tracker.streaming

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.geovault.tracker.LiveTrackStreamingService
import com.geovault.tracker.MainActivity
import com.geovault.tracker.R
import java.util.Locale

internal class LiveStreamForeground(
    private val service: Service,
    private val channelId: String,
) {
    fun build(trackerName: String?, trackerCount: Int): Notification {
        return buildNotification(trackerName, trackerCount)
    }

    fun buildFallback(): Notification {
        return NotificationCompat.Builder(service, channelId)
            .setContentTitle(service.getString(R.string.live_track_streaming_title))
            .setContentText(service.getString(R.string.live_track_streaming_text_anon))
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun buildNotification(trackerName: String?, trackerCount: Int): Notification {
        val pendingIntent = PendingIntent.getActivity(
            service,
            0,
            Intent(service, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = Intent(service, LiveStreamNotificationStopReceiver::class.java).apply {
            action = "com.geovault.tracker.LIVE_STREAM_NOTIFICATION_STOP"
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            service,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val dismissIntent = Intent(LiveTrackStreamingService.NOTIFICATION_DISMISSED_ACTION).apply {
            setPackage(service.packageName)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            service,
            2,
            dismissIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = service.getString(R.string.live_track_streaming_title)
        val text = when {
            trackerName?.isNotBlank() == true -> service.getString(R.string.live_track_streaming_text, trackerName)
            trackerCount > 1 -> String.format(
                Locale.US,
                service.getString(R.string.live_track_streaming_text_many),
                trackerCount,
            )
            else -> service.getString(R.string.live_track_streaming_text_anon)
        }
        return NotificationCompat.Builder(service, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                service.getString(R.string.streaming_notification_action_stop),
                stopPendingIntent,
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setDeleteIntent(dismissPendingIntent)
            .build()
    }
}
