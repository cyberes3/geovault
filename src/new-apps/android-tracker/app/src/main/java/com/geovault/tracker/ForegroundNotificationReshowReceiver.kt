package com.geovault.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.geovault.tracker.runtime.RuntimeCommand
import com.geovault.tracker.runtime.RuntimeCommandType
import com.geovault.tracker.runtime.RuntimeTrigger
import com.geovault.tracker.runtime.TrackingRuntimeController

class ForegroundNotificationReshowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
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
        }
    }
}
