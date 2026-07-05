package com.geovault.tracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import com.geovault.common.logging.GeoVaultCaptureLog
import java.util.Locale

/**
 * Owns every notification concern for [LiveTrackStreamingService]: channel creation, building
 * the ongoing-stream notification, and the startForeground-with-fallback dance. Extracted out of
 * the service so its connection/lifecycle logic isn't interleaved with Android notification
 * boilerplate — the service now just calls [show] whenever it needs the notification posted or
 * refreshed (initial start, roster hot-update, or a dismissed-notification reshow request).
 */
internal class StreamingForegroundNotifier(
    private val service: Service,
    private val notificationId: Int,
    private val channelId: String,
) {
    private val tag = "LiveTrackStreaming"

    fun show(trackerName: String?, trackerCount: Int) {
        ensureChannel()
        startForegroundForStreaming(buildNotification(trackerName, trackerCount))
    }

    private fun ensureChannel() {
        val manager = service.getSystemService(Service.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            channelId,
            service.getString(R.string.live_track_streaming_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = service.getString(R.string.live_track_streaming_channel_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            enableLights(false)
            setLockscreenVisibility(Notification.VISIBILITY_SECRET)
            setBypassDnd(false)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * After `startForegroundService`, the system requires `startForeground` within a short
     * deadline. Use a minimal notification if the primary notification cannot be posted.
     */
    private fun startForegroundForStreaming(notification: Notification) {
        try {
            service.startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            GeoVaultCaptureLog.e(tag, "startForeground failed; using minimal FGS notification", e)
            runCatching {
                service.startForeground(
                    notificationId,
                    buildMinimalNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            }.exceptionOrNull()?.let { inner ->
                GeoVaultCaptureLog.e(tag, "Minimal startForeground also failed", inner)
                throw inner
            }
        }
    }

    private fun buildMinimalNotification(): Notification {
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
        val stopIntent = Intent(service, LiveTrackStreamingService::class.java).apply {
            action = LiveTrackStreamingService.ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
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
