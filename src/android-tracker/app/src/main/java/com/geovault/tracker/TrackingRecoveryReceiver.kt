package com.geovault.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.runtime.TrackingRuntimeController
import com.geovault.tracker.startup.RecoveryStartupPolicy
import com.geovault.tracker.startup.RecoveryStartupSnapshot
import com.geovault.tracker.startup.RecoveryTickOutcome

class TrackingRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val app = context.applicationContext as? android.app.Application
        if (app == null) {
            GeoVaultCaptureLog.e(TAG, "Recovery receiver ignored because application context was not Application")
            return
        }
        val settingsState = TrackerAppServices.from(app).trackerSettingsRepository().getState()
        val controller = TrackingRuntimeController.get(app)
        val outcome = RecoveryStartupPolicy.evaluate(
            RecoveryStartupSnapshot(
                action = action,
                settingsLoadState = settingsState.loadState,
                wasTrackingBeforeExit = settingsState.wasTrackingBeforeExit
            )
        )
        when (outcome) {
            is RecoveryTickOutcome.Handle -> {
                val strict = controller.evaluateStrictPrerequisites()
                GeoVaultCaptureLog.d(
                    TAG,
                    "Recovery handle action=$action wasTrackingBeforeExit=${settingsState.wasTrackingBeforeExit} strictReady=${strict.isReady}"
                )
                val result = controller.handleWatchdogTick(outcome.request)
                GeoVaultCaptureLog.i(
                    TAG,
                    "Runtime recovery decision action=${result.action} reason=${result.reason} gate=${result.startGateDecision}"
                )
            }
            is RecoveryTickOutcome.Defer -> {
                GeoVaultCaptureLog.w(TAG, "Recovery deferred action=$action reason=${outcome.reason} delayMs=${outcome.delayMs}")
                controller.ensureWatchdogScheduledIn(outcome.delayMs, reason = outcome.reason)
            }
            is RecoveryTickOutcome.Stop -> {
                GeoVaultCaptureLog.w(TAG, "Recovery stopped action=$action reason=${outcome.reason}")
            }
        }
    }

    companion object {
        private const val TAG = "TrackingRecovery"
    }
}
