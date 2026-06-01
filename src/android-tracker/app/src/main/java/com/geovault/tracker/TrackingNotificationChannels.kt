package com.geovault.tracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.geovault.tracker.tracking.TrackingServiceConstants

object TrackingNotificationChannels {
    fun ensureTrackingChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            TrackingServiceConstants.CHANNEL_ID,
            context.getString(R.string.tracking_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.tracking_notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
