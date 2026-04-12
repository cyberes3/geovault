package com.geovault.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.runtime.TrackingRuntimeController
import com.geovault.tracker.startup.RecoveryStartupPolicy
import com.geovault.tracker.startup.RecoveryStartupSnapshot

class TrackingRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val app = context.applicationContext as? android.app.Application
        if (app == null) {
            Log.e(TAG, "Recovery receiver ignored because application context was not Application")
            return
        }
        val settingsRepository = TrackerAppServices.from(app).trackerSettingsRepository()
        val settingsState = settingsRepository.getState()
        val startupDecision = RecoveryStartupPolicy.evaluate(
            RecoveryStartupSnapshot(
                action = action,
                settingsLoadState = settingsState.loadState,
                wasTrackingBeforeExit = settingsState.wasTrackingBeforeExit
            )
        )
        if (!startupDecision.shouldHandleTick) {
            Log.w(
                TAG,
                "Recovery receiver skipped action=$action blockers=${startupDecision.blockers.joinToString(",") { it.logLabel }}"
            )
            return
        }
        val strictStatus = TrackingRuntimeController.get(app).evaluateStrictPrerequisites()
        Log.d(
            TAG,
            "Recovery receiver action=$action restartTrackingIfKilled=${startupDecision.request?.restartTrackingIfKilled} " +
                "wasTrackingBeforeExit=${settingsState.wasTrackingBeforeExit} strictReady=${strictStatus.isReady}"
        )
        val result = TrackingRuntimeController.get(app).handleWatchdogTick(
            request = requireNotNull(startupDecision.request)
        )
        Log.i(
            TAG,
            "Runtime recovery decision action=${result.action} reason=${result.reason} gate=${result.startGateDecision}"
        )
    }

    companion object {
        private const val TAG = "TrackingRecovery"
    }
}
