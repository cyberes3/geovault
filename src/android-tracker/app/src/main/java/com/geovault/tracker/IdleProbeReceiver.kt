package com.geovault.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.geovault.tracker.location.IdleProbeScheduler

/**
 * Routes the periodic [IdleProbeScheduler.ACTION_IDLE_PROBE] alarm into
 * [TrackingService]. This receiver intentionally owns no logic - the
 * service decides whether to act on the probe based on its current
 * runtime state.
 */
class IdleProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != IdleProbeScheduler.ACTION_IDLE_PROBE) return
        val appContext = context.applicationContext
        try {
            appContext.startService(TrackingService.buildIdleProbeIntent(appContext))
        } catch (error: IllegalStateException) {
            Log.e(TAG, "Unable to deliver idle probe to TrackingService", error)
        }
    }

    companion object {
        private const val TAG = "IdleProbeReceiver"
    }
}
