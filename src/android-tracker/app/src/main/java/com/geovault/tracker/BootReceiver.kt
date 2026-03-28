package com.geovault.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.UserManager
import android.util.Log
import com.geovault.tracker.location.TrackingPermissionGate
import com.geovault.tracker.runtime.RuntimeCommand
import com.geovault.tracker.runtime.RuntimeCommandType
import com.geovault.tracker.runtime.RuntimeTrigger
import com.geovault.tracker.runtime.TrackingSessionOrchestrator
import com.geovault.tracker.settings.TrackerSettingsLoadState
import com.geovault.tracker.settings.TrackerSettingsRepository
import com.geovault.tracker.startup.BootStartupPolicy
import com.geovault.tracker.startup.BootStartupSnapshot
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject
    lateinit var settingsRepository: TrackerSettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        val bootAction = intent.action
        val settingsState = settingsRepository.getState()
        if (!shouldProcessSettingsState(settingsState.loadState)) {
            Log.w(
                TAG,
                "Boot handling skipped action=$bootAction reason=settings_${settingsState.loadState.name.lowercase()}"
            )
            return
        }
        val settings = settingsState.settings
        val startOnBoot = settings.startOnBoot
        val wasTrackingBeforeExit = settingsState.wasTrackingBeforeExit
        val trackerId = SelectedTrackerPrefs.selectedTrackerId(context)
        val hasRequiredPermissions = TrackingPermissionGate.hasRequiredPermissionsForTracking(context)
        val gpsProviderEnabled = isGpsProviderEnabled(context)
        val userUnlocked = isUserUnlocked(context)
        Log.i(
            TAG,
            "Boot signal action=$bootAction userUnlocked=$userUnlocked " +
                "startOnBoot=$startOnBoot wasTrackingBeforeExit=$wasTrackingBeforeExit " +
                "hasRequiredPermissions=$hasRequiredPermissions gpsEnabled=$gpsProviderEnabled " +
                "hasSelectedTracker=${trackerId.isNotBlank()}"
        )

        val decision = BootStartupPolicy.evaluate(
            BootStartupSnapshot(
                action = bootAction,
                startOnBoot = startOnBoot,
                hasRequiredPermissions = hasRequiredPermissions,
                gpsProviderEnabled = gpsProviderEnabled,
                selectedTrackerId = trackerId
            )
        )

        if (!decision.shouldStartTracking) {
            Log.w(
                TAG,
                "Skipping tracking start action=$bootAction blockers=${decision.blockers.joinToString(",") { it.logLabel }}"
            )
            return
        }

        Log.i(TAG, "Starting TrackingService from action=$bootAction")
        val launchDecision = TrackingSessionOrchestrator.get(context).handleCommand(
            RuntimeCommand(
                type = RuntimeCommandType.START,
                trigger = RuntimeTrigger.BOOT,
                reason = "boot:${bootAction ?: "unknown"}"
            )
        ).commandResult
        Log.i(
            TAG,
            "Boot launch decision action=${launchDecision?.action} reason=${launchDecision?.reason} gate=${launchDecision?.startGateDecision}"
        )
    }

    companion object {
        private const val TAG = "BootReceiver"

        internal fun shouldProcessSettingsState(loadState: TrackerSettingsLoadState): Boolean {
            return loadState == TrackerSettingsLoadState.Ready
        }

        fun shouldStartTrackingOnBoot(
            startOnBoot: Boolean,
            hasRequiredPermissions: Boolean,
            gpsProviderEnabled: Boolean,
            selectedTrackerId: String
        ): Boolean {
            val decision = BootStartupPolicy.evaluate(
                BootStartupSnapshot(
                    action = Intent.ACTION_BOOT_COMPLETED,
                    startOnBoot = startOnBoot,
                    hasRequiredPermissions = hasRequiredPermissions,
                    gpsProviderEnabled = gpsProviderEnabled,
                    selectedTrackerId = selectedTrackerId
                )
            )
            return decision.shouldStartTracking
        }

        private fun isGpsProviderEnabled(context: Context): Boolean {
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
