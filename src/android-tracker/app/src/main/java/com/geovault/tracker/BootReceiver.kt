package com.geovault.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
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
            if (startOnBoot) {
                Log.d("BootReceiver", "Starting TrackingService on boot")
                val serviceIntent = Intent(context, TrackingService::class.java).apply {
                    action = TrackingService.ACTION_START
                }
                context.startForegroundService(serviceIntent)
            }
        }
    }
}
