package com.geovault.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.util.Log
import com.geovault.tracker.location.TrackingPermissionGate
import com.geovault.tracker.settings.TrackerSettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject
    lateinit var settingsRepository: TrackerSettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Boot completed, checking if tracking should start")
            val settings = settingsRepository.getSettings()
            val startOnBoot = settings.startOnBoot
            val wasTrackingBeforeExit = settingsRepository.wasTrackingBeforeExit()
            val trackerId = SelectedTrackerPrefs.selectedTrackerId(context)
            val hasRequiredPermissions = TrackingPermissionGate.hasRequiredPermissionsForTracking(context)
            val gpsProviderEnabled = isGpsProviderEnabled(context)
            val strictPrereqs = TrackingRecoveryCoordinator.evaluateStrictPrerequisites(context.applicationContext)

            if (settings.resetTrackingIfKilled && wasTrackingBeforeExit) {
                if (!strictPrereqs.isReady) {
                    Log.w(
                        "BootReceiver",
                        "Strict recovery prerequisites missing at boot: " +
                            "exactAlarm=${strictPrereqs.hasExactAlarmAccess} " +
                            "batteryExempt=${strictPrereqs.hasBatteryOptimizationExemption}"
                    )
                }
                TrackingRecoveryCoordinator.ensureWatchdogScheduled(context.applicationContext)
            }

            if (!shouldStartTrackingOnBoot(startOnBoot, hasRequiredPermissions, gpsProviderEnabled, trackerId)) {
                Log.w("BootReceiver", "Skipping tracking start on boot: prerequisites missing")
                return
            }
            Log.d("BootReceiver", "Starting TrackingService on boot")
            val serviceIntent = Intent(context, TrackingService::class.java).apply {
                action = TrackingService.ACTION_START
            }
            context.startForegroundService(serviceIntent)
        }
    }

    companion object {
        fun shouldStartTrackingOnBoot(
            startOnBoot: Boolean,
            hasRequiredPermissions: Boolean,
            gpsProviderEnabled: Boolean,
            selectedTrackerId: String
        ): Boolean {
            if (!startOnBoot) return false
            if (!hasRequiredPermissions) return false
            if (!gpsProviderEnabled) return false
            return TrackingService.hasValidSelectedTrackerId(selectedTrackerId)
        }

        private fun isGpsProviderEnabled(context: Context): Boolean {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
            return try {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            } catch (_: Exception) {
                false
            }
        }
    }
}
