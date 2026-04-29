package com.geovault.tracker

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.util.Log
import com.geovault.tracker.runtime.TrackingRuntimeController
import com.google.android.gms.location.LocationResult

class TrackingLocationUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val locations = extractLocations(intent)
        if (locations.isEmpty()) return

        val appContext = context.applicationContext
        try {
            appContext.startService(
                TrackingService.buildLocationUpdateIntent(
                    context = appContext,
                    locations = locations
                )
            )
        } catch (error: IllegalStateException) {
            Log.e(TAG, "Unable to deliver fused location update to active tracking service", error)
            TrackingRuntimeController.get(appContext).ensureWatchdogScheduled()
        }
    }

    companion object {
        private const val TAG = "TrackingLocationReceiver"
        private const val REQUEST_CODE = 23001
        const val ACTION_FUSED_LOCATION_UPDATE = "com.geovault.tracker.ACTION_FUSED_LOCATION_UPDATE"

        fun pendingIntent(context: Context): PendingIntent {
            val appContext = context.applicationContext
            val intent = Intent(appContext, TrackingLocationUpdateReceiver::class.java).apply {
                action = ACTION_FUSED_LOCATION_UPDATE
                setPackage(appContext.packageName)
            }
            return PendingIntent.getBroadcast(
                appContext,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
        }

        fun extractLocations(intent: Intent?): List<Location> {
            if (intent?.action != ACTION_FUSED_LOCATION_UPDATE) return emptyList()
            val result = LocationResult.extractResult(intent) ?: return emptyList()
            return result.locations.map { Location(it) }
        }
    }
}
