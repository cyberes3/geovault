package com.geovault.tracker.services

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.geovault.tracker.MainActivity
import com.geovault.tracker.R
import com.geovault.tracker.tracking.TrackingService
import com.geovault.tracker.tracking.TrackingServiceConstants
import com.geovault.tracker.tracking.TrackingServiceIntents

class TrackingNotificationPresenter(private val context: Context) {
    fun buildTrackingNotification(snapshot: TrackingRuntimeSnapshot): Notification {
        return buildTrackingNotification(
            sentCount = snapshot.pointsSentThisSession,
            queuedCount = snapshot.queuedPointsVisible,
            uiStatus = snapshot.uiStatus
        )
    }

    fun buildTrackingNotification(sentCount: Int, queuedCount: Int, uiStatus: TrackingUiStatus): Notification {
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(context, TrackingService::class.java).apply {
            action = TrackingServiceIntents.ACTION_STOP
            setPackage(context.packageName)
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        val dismissIntent = Intent(TrackingServiceIntents.NOTIFICATION_DISMISSED_ACTION).apply {
            setPackage(context.packageName)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context,
            2,
            dismissIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val status = context.getString(statusTextRes(uiStatus))
        val counts = context.getString(R.string.tracking_notification_counts_line, sentCount, queuedCount)
        val text = "$status\n$counts"
        return NotificationCompat.Builder(context, TrackingServiceConstants.CHANNEL_ID)
            .setContentTitle(context.getString(R.string.live_tracker_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .addAction(0, context.getString(R.string.tracking_notification_action_stop), stopPendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setDeleteIntent(dismissPendingIntent)
            .build()
    }

    fun updateForegroundNotification(sentCount: Int, queuedCount: Int, uiStatus: TrackingUiStatus) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            TrackingServiceConstants.NOTIFICATION_ID,
            buildTrackingNotification(sentCount, queuedCount, uiStatus)
        )
    }

    fun updateForegroundNotification(snapshot: TrackingRuntimeSnapshot) {
        updateForegroundNotification(
            sentCount = snapshot.pointsSentThisSession,
            queuedCount = snapshot.queuedPointsVisible,
            uiStatus = snapshot.uiStatus
        )
    }

    private fun statusTextRes(status: TrackingUiStatus): Int {
        return when (status) {
            TrackingUiStatus.NOT_TRACKING -> R.string.tracking_status_not_tracking
            TrackingUiStatus.WAITING_FOR_GPS -> R.string.tracking_status_waiting_for_gps
            TrackingUiStatus.LOCKING -> R.string.tracking_status_locking
            TrackingUiStatus.PAUSED_FOR_MOTION -> R.string.tracking_status_active
            TrackingUiStatus.TRACKING_ACTIVE -> R.string.tracking_status_active
        }
    }
}
