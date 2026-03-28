package com.geovault.tracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.util.Log
import com.geovault.tracker.runtime.RuntimeTrigger
import com.geovault.tracker.runtime.TrackingRuntimeController

/**
 * Recovery coordinator for runtime-only recovery responsibilities.
 *
 * This object intentionally delegates lifecycle decisions to the runtime domain layer.
 */
object TrackingRecoveryCoordinator {
    private const val TAG = "TrackingRecovery"
    private const val PREFS_NAME = "tracking_recovery_state_v3"
    private const val KEY_TELEMETRY_RING = "recovery_telemetry_ring"
    private const val MAX_TELEMETRY_ENTRIES = 300

    const val ACTION_RECOVERY_TICK = "com.geovault.tracker.ACTION_RECOVERY_TICK"
    const val ACTION_OPEN_APP_FROM_RECOVERY = "com.geovault.tracker.ACTION_OPEN_APP_FROM_RECOVERY"
    const val CHANNEL_ID_RECOVERY = "tracking_recovery_alerts"

    @JvmStatic
    fun markTrackingStarted(context: Context) {
        TrackingRuntimeController.get(context).markTrackingStarted(RuntimeTrigger.EXPLICIT_START)
        recordTelemetry(context, "markTrackingStarted")
        ensureWatchdogScheduled(context)
    }

    @JvmStatic
    fun markHeartbeat(context: Context) {
        TrackingRuntimeController.get(context).markHeartbeat()
        recordTelemetry(context, "markHeartbeat")
    }

    @JvmStatic
    fun markIntentionalStop(context: Context, reason: String = "intentional_stop") {
        TrackingRuntimeController.get(context).markIntentionalStop(reason)
        recordTelemetry(context, "markIntentionalStop reason=$reason")
        cancelWatchdog(context)
    }

    @JvmStatic
    fun markUnexpectedDestroy(context: Context, wasTracking: Boolean) {
        TrackingRuntimeController.get(context).markUnexpectedDestroy(wasTracking)
        recordTelemetry(context, "markUnexpectedDestroy wasTracking=$wasTracking")
        if (wasTracking) {
            ensureWatchdogScheduled(context)
        }
    }

    @JvmStatic
    fun ensureWatchdogScheduled(context: Context) {
        TrackingRuntimeController.get(context).ensureWatchdogScheduled()
        Log.d(TAG, "watchdog_scheduled")
        recordTelemetry(context, "watchdog_scheduled")
    }

    @JvmStatic
    fun cancelWatchdog(context: Context) {
        TrackingRuntimeController.get(context).cancelWatchdog()
        Log.d(TAG, "watchdog_canceled")
        recordTelemetry(context, "watchdog_canceled")
    }

    @JvmStatic
    fun dumpTelemetryToLogcat(context: Context, reason: String = "manual") {
        val entries = prefs(context).getString(KEY_TELEMETRY_RING, "")
            .orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()
        Log.i(TAG, "Telemetry dump requested reason=$reason entries=${entries.size}")
        if (entries.isEmpty()) {
            Log.i(TAG, "Telemetry dump is empty")
            return
        }
        entries.forEachIndexed { index, entry ->
            Log.i(TAG, "Telemetry[${index + 1}/${entries.size}] $entry")
        }
    }

    @JvmStatic
    fun createRecoveryChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID_RECOVERY,
            context.getString(R.string.recovery_channel_name),
            NotificationManager.IMPORTANCE_HIGH
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
    private fun recordTelemetry(context: Context, event: String) {
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
