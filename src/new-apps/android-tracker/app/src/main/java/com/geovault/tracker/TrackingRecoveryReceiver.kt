package com.geovault.tracker

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.runtime.TrackingRuntimeController
import com.geovault.tracker.settings.TrackerSettingsLoadState

class TrackingRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != TrackingRecoveryCoordinator.ACTION_RECOVERY_TICK &&
            action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        ) {
            return
        }
        val app = context.applicationContext
        val settingsRepository = TrackerAppServices.from(app as android.app.Application).trackerSettingsRepository()
        val settingsState = settingsRepository.getState()
        if (!shouldProcessSettingsState(settingsState.loadState)) {
            Log.w(
                TAG,
                "Recovery receiver skipped action=$action reason=settings_${settingsState.loadState.name.lowercase()}"
            )
            return
        }
        val wasTrackingBeforeExit = settingsState.wasTrackingBeforeExit
        Log.d(
            TAG,
            "Recovery receiver action=$action restartTrackingIfKilled=false wasTrackingBeforeExit=$wasTrackingBeforeExit"
        )
        val result = TrackingRuntimeController.get(app).handleWatchdogTick(
            restartTrackingIfKilled = false,
            wasTrackingBeforeExit = wasTrackingBeforeExit
        )
        Log.i(
            TAG,
            "Runtime recovery decision action=${result.action} reason=${result.reason} gate=${result.startGateDecision}"
        )
    }

    companion object {
        private const val TAG = "TrackingRecovery"

        internal fun shouldProcessSettingsState(loadState: TrackerSettingsLoadState): Boolean {
            return loadState == TrackerSettingsLoadState.Ready
        }
    }
}
