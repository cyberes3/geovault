package com.geovault.tracker.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.R

object RecoveryTelemetry {
    private const val TAG = "TrackingRecovery"
    private const val PREFS_NAME = "tracking_recovery_state_v3"
    private const val KEY_TELEMETRY_RING = "recovery_telemetry_ring"
    private const val MAX_TELEMETRY_ENTRIES = 300

    const val ACTION_RECOVERY_TICK = "com.geovault.tracker.ACTION_RECOVERY_TICK"
    const val ACTION_OPEN_APP_FROM_RECOVERY = "com.geovault.tracker.ACTION_OPEN_APP_FROM_RECOVERY"
    const val CHANNEL_ID_RECOVERY = "tracking_recovery_alerts"

    fun dumpToLogcat(context: Context, reason: String = "manual") {
        val entries = prefs(context).getString(KEY_TELEMETRY_RING, "")
            .orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()
        GeoVaultCaptureLog.i(TAG, "Telemetry dump requested reason=$reason entries=${entries.size}")
        if (entries.isEmpty()) {
            GeoVaultCaptureLog.i(TAG, "Telemetry dump is empty")
            return
        }
        entries.forEachIndexed { index, entry ->
            GeoVaultCaptureLog.i(TAG, "Telemetry[${index + 1}/${entries.size}] $entry")
        }
    }

    fun createRecoveryChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID_RECOVERY,
            context.getString(R.string.recovery_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.recovery_channel_description)
            enableVibration(true)
            setBypassDnd(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, attrs)
        }
        manager.createNotificationChannel(channel)
    }

    @Synchronized
    fun record(context: Context, event: String) {
        val existing = prefs(context).getString(KEY_TELEMETRY_RING, "")
            .orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .toMutableList()
        val now = System.currentTimeMillis()
        existing.add("$now | $event")
        val trimmed = if (existing.size > MAX_TELEMETRY_ENTRIES) {
            existing.takeLast(MAX_TELEMETRY_ENTRIES)
        } else {
            existing
        }
        prefs(context).edit().putString(KEY_TELEMETRY_RING, trimmed.joinToString("\n")).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
