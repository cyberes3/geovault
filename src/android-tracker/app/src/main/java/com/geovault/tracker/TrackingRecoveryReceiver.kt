package com.geovault.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.location.TrackingPermissionGate
import com.geovault.tracker.runtime.RecoveryInput
import com.geovault.tracker.runtime.RecoverySource
import com.geovault.tracker.runtime.TrackerRuntimeEngine
import com.geovault.tracker.runtime.TrackerRuntimeStore

class TrackingRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val app = context.applicationContext as? android.app.Application
        if (app == null) {
            GeoVaultCaptureLog.e(TAG, "Recovery receiver ignored because application context was not Application")
            return
        }
        val services = TrackerAppServices.from(app)
        val settingsState = services.trackerSettingsRepository().getState()
        val engine = TrackerRuntimeEngine.get(app)
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val result = engine.handleWatchdogTick(
            RecoveryInput(
                source = RecoverySource.WatchdogTick,
                shouldBeRunning = TrackerRuntimeStore.value.shouldBeRunning,
                restartTrackingIfKilled = true,
                startOnBoot = settingsState.settings.startOnBoot,
                serviceRunning = engine.isRunning(),
                settingsLoadState = settingsState.loadState,
                userUnlocked = true,
                hasRequiredPermissions = TrackingPermissionGate.hasRequiredPermissionsForTracking(context),
                gpsProviderEnabled = locationManager != null &&
                    locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER),
                selectedTrackerId = services.catalogSelectionController().selectedTrackerId(context),
                lastHeartbeatAtMs = TrackerRuntimeStore.value.orchestration.lastHeartbeatAtMs,
                nowMs = System.currentTimeMillis(),
            ),
        )
        GeoVaultCaptureLog.i(TAG, "Runtime recovery action=$action reason=${result.reason} gate=${result.startGateDecision}")
    }

    companion object {
        private const val TAG = "TrackingRecovery"
    }
}
