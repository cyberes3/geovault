package com.geovault.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.UserManager
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.location.TrackingPermissionGate
import com.geovault.tracker.runtime.RecoveryDecision
import com.geovault.tracker.runtime.RecoveryInput
import com.geovault.tracker.runtime.RecoveryPolicy
import com.geovault.tracker.runtime.RecoverySource
import com.geovault.tracker.runtime.TrackerRuntimeEngine
import com.geovault.tracker.runtime.TrackerRuntimeStore
import com.geovault.tracker.settings.TrackerSettingsLoadState

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val bootAction = intent.action
        if (bootAction !in SUPPORTED_ACTIONS) {
            GeoVaultCaptureLog.w(TAG, "Boot handling skipped action=$bootAction reason=unsupported_action")
            return
        }
        val app = context.applicationContext as? android.app.Application
        if (app == null) {
            GeoVaultCaptureLog.e(TAG, "Boot handling ignored because application context was not Application")
            return
        }
        val services = TrackerAppServices.from(app)
        val settingsState = services.trackerSettingsRepository().getState()
        if (!shouldProcessSettingsState(settingsState.loadState)) {
            GeoVaultCaptureLog.w(
                TAG,
                "Boot handling skipped action=$bootAction reason=settings_${settingsState.loadState.name.lowercase()}"
            )
            return
        }
        TrackerRuntimeStore.attach(app)
        val engine = TrackerRuntimeEngine.get(app)
        val selectedTrackerId = services.catalogSelectionController().selectedTrackerId(context)
        val hasRequiredPermissions = TrackingPermissionGate.hasRequiredPermissionsForTracking(context)
        val gpsProviderEnabled = isGpsProviderEnabled(context)
        val userUnlocked = isUserUnlocked(context)
        GeoVaultCaptureLog.i(
            TAG,
            "Boot signal action=$bootAction userUnlocked=$userUnlocked " +
                "startOnBoot=${settingsState.settings.startOnBoot} " +
                "shouldBeRunning=${TrackerRuntimeStore.value.shouldBeRunning} " +
                "hasRequiredPermissions=$hasRequiredPermissions gpsEnabled=$gpsProviderEnabled " +
                "hasSelectedTracker=${selectedTrackerId.isNotBlank()}"
        )
        val result = engine.handleRecoverySource(
            RecoveryInput(
                source = RecoverySource.Boot,
                shouldBeRunning = TrackerRuntimeStore.value.shouldBeRunning,
                restartTrackingIfKilled = true,
                startOnBoot = settingsState.settings.startOnBoot,
                serviceRunning = engine.isRunning(),
                settingsLoadState = settingsState.loadState,
                userUnlocked = userUnlocked,
                hasRequiredPermissions = hasRequiredPermissions,
                gpsProviderEnabled = gpsProviderEnabled,
                selectedTrackerId = selectedTrackerId,
                lastHeartbeatAtMs = TrackerRuntimeStore.value.orchestration.lastHeartbeatAtMs,
                nowMs = System.currentTimeMillis(),
            ),
        )
        GeoVaultCaptureLog.i(
            TAG,
            "Boot launch decision action=${result.action} reason=${result.reason} gate=${result.startGateDecision}"
        )
    }

    companion object {
        private const val TAG = "BootReceiver"
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )

        internal fun shouldProcessSettingsState(loadState: TrackerSettingsLoadState): Boolean {
            return loadState == TrackerSettingsLoadState.Ready
        }

        fun shouldStartTrackingOnBoot(
            startOnBoot: Boolean,
            shouldBeRunning: Boolean,
            userUnlocked: Boolean,
            hasRequiredPermissions: Boolean,
            gpsProviderEnabled: Boolean,
            selectedTrackerId: String
        ): Boolean {
            val decision = RecoveryPolicy.decide(
                RecoveryInput(
                    source = RecoverySource.Boot,
                    shouldBeRunning = shouldBeRunning,
                    restartTrackingIfKilled = true,
                    startOnBoot = startOnBoot,
                    serviceRunning = false,
                    settingsLoadState = TrackerSettingsLoadState.Ready,
                    userUnlocked = userUnlocked,
                    hasRequiredPermissions = hasRequiredPermissions,
                    gpsProviderEnabled = gpsProviderEnabled,
                    selectedTrackerId = selectedTrackerId,
                    lastHeartbeatAtMs = 0L,
                    nowMs = System.currentTimeMillis(),
                ),
            )
            return decision is RecoveryDecision.AttemptStart
        }

        fun isGpsProviderEnabled(context: Context): Boolean {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
            return try {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            } catch (_: Exception) {
                false
            }
        }

        private fun isUserUnlocked(context: Context): Boolean {
            val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager ?: return false
            return try {
                userManager.isUserUnlocked
            } catch (_: Exception) {
                false
            }
        }
    }
}
