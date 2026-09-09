package com.geovault.common.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.geovault.common.logging.GeoVaultCaptureLog

/**
 * Exact-alarm watchdog. The app supplies the tick [Intent] and evaluates prerequisites before
 * acting on a fire. [cancel] must run on intentional stop and account reset.
 */
class GeoVaultRuntimeWatchdog(
    context: Context,
    private val config: Config,
    private val tickIntentFactory: () -> Intent,
    private val elapsedClock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    data class Config(
        val requestCode: Int,
        val tag: String,
        val intervalMs: Long = DEFAULT_INTERVAL_MS,
        val deliverAsBroadcast: Boolean = true,
    )

    private val appContext = context.applicationContext

    fun schedule(delayMs: Long = config.intervalMs) {
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = elapsedClock() + delayMs
        val pending = tickPendingIntent()
        if (canScheduleExactAlarms(alarmManager)) {
            GeoVaultCaptureLog.d(config.tag, "schedule mode=exact delayMs=$delayMs triggerAtElapsed=$triggerAt")
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pending,
            )
        } else {
            GeoVaultCaptureLog.d(config.tag, "schedule mode=inexact delayMs=$delayMs triggerAtElapsed=$triggerAt")
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pending,
            )
        }
    }

    fun cancel() {
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(tickPendingIntent())
        GeoVaultCaptureLog.d(config.tag, "cancel watchdog alarm")
    }

    fun canScheduleExact(): Boolean {
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return canScheduleExactAlarms(alarmManager)
    }

    private fun tickPendingIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val intent = tickIntentFactory()
        return if (config.deliverAsBroadcast) {
            PendingIntent.getBroadcast(appContext, config.requestCode, intent, flags)
        } else {
            PendingIntent.getService(appContext, config.requestCode, intent, flags)
        }
    }

    private fun canScheduleExactAlarms(alarmManager: AlarmManager): Boolean {
        return try {
            alarmManager.canScheduleExactAlarms()
        } catch (_: SecurityException) {
            false
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 15_000L
    }
}
