package com.geovault.tracker

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.geovault.tracker.settings.TrackerSettingsRepositoryImpl

class TrackingRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != TrackingRecoveryCoordinator.ACTION_RECOVERY_TICK &&
            action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        ) {
            return
        }
        val prefs = context.applicationContext.getSharedPreferences(
            TrackerSettingsRepositoryImpl.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val restartTrackingIfKilled = prefs.getBoolean(
            TrackerSettingsRepositoryImpl.KEY_RESTART_TRACKING_IF_KILLED,
            true
        )
        val wasTrackingBeforeExit = prefs.getBoolean(
            TrackerSettingsRepositoryImpl.KEY_WAS_TRACKING_BEFORE_EXIT,
            false
        )
        Log.d(
            "TrackingRecovery",
            "Recovery receiver action=$action restartTrackingIfKilled=$restartTrackingIfKilled wasTrackingBeforeExit=$wasTrackingBeforeExit"
        )
        TrackingRecoveryCoordinator.handleRecoveryTick(
            context = context.applicationContext,
            restartTrackingIfKilled = restartTrackingIfKilled,
            wasTrackingBeforeExit = wasTrackingBeforeExit
        )
    }
}
