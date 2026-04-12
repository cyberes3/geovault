package com.geovault.tracker.runtime

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.geovault.tracker.TrackingRecoveryCoordinator
import com.geovault.tracker.TrackingRecoveryReceiver

class WatchdogScheduler(context: Context) {
    private val appContext = context.applicationContext

    fun schedule(delayMs: Long = RECOVERY_INTERVAL_MS) {
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = SystemClock.elapsedRealtime() + delayMs
        if (canScheduleExactAlarms(alarmManager)) {
            Log.d(TAG, "schedule mode=exact delayMs=$delayMs triggerAtElapsed=$triggerAt")
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent()
            )
        } else {
            Log.d(TAG, "schedule mode=inexact delayMs=$delayMs triggerAtElapsed=$triggerAt")
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent()
            )
        }
    }

    fun cancel() {
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent())
        Log.d(TAG, "cancel watchdog alarm")
    }

    fun canScheduleExact(): Boolean {
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return canScheduleExactAlarms(alarmManager)
    }

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(appContext, TrackingRecoveryReceiver::class.java).apply {
            action = TrackingRecoveryCoordinator.ACTION_RECOVERY_TICK
            setPackage(appContext.packageName)
        }
        return PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun canScheduleExactAlarms(alarmManager: AlarmManager): Boolean {
        return try {
            alarmManager.canScheduleExactAlarms()
        } catch (_: SecurityException) {
            false
        }
    }

    companion object {
        private const val TAG = "TrackingWatchdogV2"
        const val RECOVERY_INTERVAL_MS = 5_000L
        private const val REQUEST_CODE = 20101
    }
}
