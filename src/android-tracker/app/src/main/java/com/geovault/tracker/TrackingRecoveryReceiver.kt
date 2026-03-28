package com.geovault.tracker

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.geovault.tracker.runtime.TrackingSessionOrchestrator
import com.geovault.tracker.settings.TrackerSettingsLoadState
import com.geovault.tracker.settings.TrackerSettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class TrackingRecoveryReceiver : BroadcastReceiver() {
    @Inject
    lateinit var settingsRepository: TrackerSettingsRepository

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != TrackingRecoveryCoordinator.ACTION_RECOVERY_TICK &&
            action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        ) {
            return
        }
        val settingsState = settingsRepository.getState()
        if (!shouldProcessSettingsState(settingsState.loadState)) {
            Log.w(
                "TrackingRecovery",
                "Recovery receiver skipped action=$action reason=settings_${settingsState.loadState.name.lowercase()}"
            )
            return
        }
        val wasTrackingBeforeExit = settingsState.wasTrackingBeforeExit
        Log.d(
            "TrackingRecovery",
            "Recovery receiver action=$action restartTrackingIfKilled=false wasTrackingBeforeExit=$wasTrackingBeforeExit"
        )
        val result = TrackingSessionOrchestrator.get(context.applicationContext).handleWatchdogTick(
            restartTrackingIfKilled = false,
            wasTrackingBeforeExit = wasTrackingBeforeExit
        ).commandResult
        Log.i(
            "TrackingRecovery",
            "Runtime recovery decision action=${result?.action} reason=${result?.reason} gate=${result?.startGateDecision}"
        )
    }

    companion object {
        internal fun shouldProcessSettingsState(loadState: TrackerSettingsLoadState): Boolean {
            return loadState == TrackerSettingsLoadState.Ready
        }
    }
}
