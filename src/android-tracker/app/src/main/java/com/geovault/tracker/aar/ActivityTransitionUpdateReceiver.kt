package com.geovault.tracker.aar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.geovault.common.logging.GeoVaultCaptureLog
import com.google.android.gms.location.ActivityTransitionResult

/**
 * Receives GMS activity-transition broadcast intents and forwards events to
 * [ActivityRecognitionHintBridge.instance]. Stateless and side-effect-free when
 * no bridge instance is registered.
 */
class ActivityTransitionUpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val hasResult = ActivityTransitionResult.hasResult(intent)
        GeoVaultCaptureLog.d(TAG, "aar_receiver_invoked hasResult=$hasResult")
        if (!hasResult) {
            GeoVaultCaptureLog.w(TAG, "ActivityTransitionUpdateReceiver: intent has no result, ignoring")
            return
        }
        val result = ActivityTransitionResult.extractResult(intent) ?: run {
            GeoVaultCaptureLog.w(TAG, "ActivityTransitionUpdateReceiver: extractResult returned null, ignoring")
            return
        }
        val bridge = ActivityRecognitionHintBridge.instance
        if (bridge == null) {
            GeoVaultCaptureLog.d(TAG, "ActivityTransitionUpdateReceiver: no active bridge instance, dropping ${result.transitionEvents.size} events")
            return
        }
        for (event in result.transitionEvents) {
            bridge.onTransition(event)
        }
    }

    private companion object {
        private const val TAG = "GeoVaultAAR"
    }
}
