package com.geovault.tracker.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Shared launchers for the handful of app-specific system settings screens the tracker links
 * out to. Kept in one place (rather than duplicated per-screen `private fun`s) so the intent
 * construction -- package URI, `FLAG_ACTIVITY_NEW_TASK` -- only needs to be right once. Used by
 * both [HomeScreen]'s permission gate and the map's [MapBatteryOptimizationHint].
 */
object TrackerSystemSettingsIntents {
    fun openBatteryOptimizationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
