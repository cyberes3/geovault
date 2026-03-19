package com.geovault.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
            val startOnBoot = settingsRepository.getSettings().startOnBoot
            val trackerId = SelectedTrackerPrefs.selectedTrackerId(context)
            val hasRequiredPermissions = TrackingPermissionGate.hasRequiredPermissionsForTracking(context)
            if (!shouldStartTrackingOnBoot(startOnBoot, hasRequiredPermissions, trackerId)) {
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
            selectedTrackerId: String
        ): Boolean {
            if (!startOnBoot) return false
            if (!hasRequiredPermissions) return false
            return TrackingService.hasValidSelectedTrackerId(selectedTrackerId)
        }
    }
}
