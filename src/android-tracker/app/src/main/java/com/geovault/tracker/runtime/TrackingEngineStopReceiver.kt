package com.geovault.tracker.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TrackingEngineStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != TrackerRuntimeCommands.ACTION_ENGINE_STOP) return
        val reason = intent.getStringExtra(EXTRA_REASON) ?: "notification_stop"
        TrackerRuntimeEngine.get(context).handle(TrackerRuntimeCommands.Stop(reason = reason))
    }

    companion object {
        const val EXTRA_REASON = "reason"
    }
}
