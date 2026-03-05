package com.geovault.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Boot completed, checking if tracking should start")
            val prefs = context.getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
            val startOnBoot = prefs.getBoolean("start_on_boot", false)
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
