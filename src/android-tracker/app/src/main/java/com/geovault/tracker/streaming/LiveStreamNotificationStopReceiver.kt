package com.geovault.tracker.streaming

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.geovault.tracker.di.TrackerAppServices

class LiveStreamNotificationStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val application = context.applicationContext as Application
        TrackerAppServices.from(application)
            .liveStreamSubscriptionRepository()
            .clearAllLeases(ClearReason.NOTIFICATION)
    }
}
