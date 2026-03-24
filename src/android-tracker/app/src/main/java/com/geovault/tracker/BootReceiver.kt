package com.geovault.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.UserManager
import android.util.Log
import com.geovault.tracker.location.TrackingPermissionGate
import com.geovault.tracker.settings.TrackerSettingsRepository
import com.geovault.tracker.startup.BootStartupPolicy
import com.geovault.tracker.startup.BootStartupSnapshot
import com.geovault.tracker.startup.TrackingServiceLaunchGate
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject
    lateinit var settingsRepository: TrackerSettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        val bootAction = intent.action
        val appContext = context.applicationContext
        val settings = settingsRepository.getSettings()
        val startOnBoot = settings.startOnBoot
        val wasTrackingBeforeExit = settingsRepository.wasTrackingBeforeExit()
        val trackerId = SelectedTrackerPrefs.selectedTrackerId(context)
        val hasRequiredPermissions = TrackingPermissionGate.hasRequiredPermissionsForTracking(context)
        val gpsProviderEnabled = isGpsProviderEnabled(context)
        val strictPrereqs = TrackingRecoveryCoordinator.evaluateStrictPrerequisites(appContext)
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

        if (settings.resetTrackingIfKilled && wasTrackingBeforeExit) {
            if (!strictPrereqs.isReady) {
                Log.w(
                    TAG,
                    "Strict recovery prerequisites missing at boot: " +
                        "exactAlarm=${strictPrereqs.hasExactAlarmAccess} " +
                        "batteryExempt=${strictPrereqs.hasBatteryOptimizationExemption}"
                )
            }
            TrackingRecoveryCoordinator.ensureWatchdogScheduled(appContext)
            Log.i(TAG, "Recovery watchdog ensured at boot")
        }

        if (!decision.shouldStartTracking) {
            Log.w(
                TAG,
                "Skipping tracking start action=$bootAction blockers=${decision.blockers.joinToString(",") { it.logLabel }}"
            )
            return
        }

        Log.i(TAG, "Starting TrackingService from action=$bootAction")
        val launchDecision = TrackingServiceLaunchGate.dispatchStart(
            context = context,
            trigger = "boot:${bootAction ?: "unknown"}"
        )
        Log.i(
            TAG,
            "Boot launch decision allowed=${launchDecision.allowed} retryInMs=${launchDecision.retryInMs} reason=${launchDecision.reason}"
        )
    }

    companion object {
        private const val TAG = "BootReceiver"

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
