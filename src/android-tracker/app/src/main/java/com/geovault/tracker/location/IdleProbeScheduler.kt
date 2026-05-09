package com.geovault.tracker.location

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.geovault.tracker.IdleProbeReceiver

/**
 * Schedules periodic idle probes while GPS is paused for stationarity.
 *
 * The probe is a single broadcast that arrives on a manifest-registered
 * [IdleProbeReceiver]. The receiver hands control back to [com.geovault.tracker.TrackingService]
 * which decides whether to act on the probe (resume GPS to refresh
 * evidence). The scheduler itself owns no behavior beyond arming and
 * cancelling the alarm.
 *
 * The alarm uses [AlarmManager.setExactAndAllowWhileIdle] so it fires
 * through Doze. Battery-optimization exemption is required for this to be
 * reliable, which the tracker already enforces as a strict prerequisite
 * (see TrackingRuntimeController.evaluateStrictPrerequisites).
 *
 * Idempotent: calling [schedule] replaces any existing pending alarm,
 * calling [cancel] is a no-op when nothing is scheduled.
 */
class IdleProbeScheduler(
    context: Context,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    private val appContext: Context = context.applicationContext
    private val alarmManager: AlarmManager =
        appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun schedule() {
        val triggerAt = SystemClock.elapsedRealtime() + intervalMs
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                buildPendingIntent(),
            )
            Log.d(TAG, "Idle probe scheduled in ${intervalMs}ms")
        } catch (e: SecurityException) {
            Log.e(TAG, "Idle probe alarm rejected by OS", e)
        }
    }

    fun cancel() {
        alarmManager.cancel(buildPendingIntent())
    }

    private fun buildPendingIntent(): PendingIntent {
        val intent = Intent(ACTION_IDLE_PROBE)
            .setPackage(appContext.packageName)
            .setClass(appContext, IdleProbeReceiver::class.java)
        return PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_IDLE_PROBE = "com.geovault.tracker.ACTION_IDLE_PROBE"
        const val DEFAULT_INTERVAL_MS = 5L * 60_000L
        private const val REQUEST_CODE = 0x10E10E
        private const val TAG = "IdleProbeScheduler"
    }
}
