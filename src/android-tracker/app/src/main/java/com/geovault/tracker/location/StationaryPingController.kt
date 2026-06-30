package com.geovault.tracker.location

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.geovault.tracker.tracking.TrackingService
import com.geovault.tracker.tracking.TrackingServiceIntents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

interface StationaryPingActions {
    fun requestProbe(reason: String)
    fun logEvent(name: String, details: String)
}

interface StationaryPingClock {
    fun elapsedRealtimeMs(): Long
}

object AndroidStationaryPingClock : StationaryPingClock {
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
}

/**
 * Wakes the device to dispatch a stationary ping even if the CPU is asleep and no other
 * event happens to wake it first. A plain coroutine `delay()` has no power to wake a sleeping
 * CPU — it only resumes whenever the device next wakes for some unrelated reason, which can
 * leave the "5-minute ceiling" on a stationary pause unbounded in practice. This mirrors the
 * [com.geovault.tracker.runtime.WatchdogScheduler] pattern already used elsewhere for the same
 * Doze-safe wake guarantee.
 */
interface StationaryPingAlarmScheduler {
    fun schedule(triggerAtElapsedMs: Long)
    fun cancel()
}

object NoOpStationaryPingAlarmScheduler : StationaryPingAlarmScheduler {
    override fun schedule(triggerAtElapsedMs: Long) = Unit
    override fun cancel() = Unit
}

class AndroidStationaryPingAlarmScheduler(context: Context) : StationaryPingAlarmScheduler {
    private val appContext = context.applicationContext

    override fun schedule(triggerAtElapsedMs: Long) {
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (canScheduleExactAlarms(alarmManager)) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtElapsedMs,
                pendingIntent(),
            )
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtElapsedMs,
                pendingIntent(),
            )
        }
    }

    override fun cancel() {
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent())
    }

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(appContext, TrackingService::class.java).apply {
            action = TrackingServiceIntents.ACTION_STATIONARY_PING_DUE
            setPackage(appContext.packageName)
        }
        return PendingIntent.getService(
            appContext,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canScheduleExactAlarms(alarmManager: AlarmManager): Boolean {
        return try {
            alarmManager.canScheduleExactAlarms()
        } catch (_: SecurityException) {
            false
        }
    }

    private companion object {
        private const val REQUEST_CODE = 21001
    }
}

class StationaryPingController(
    private val scope: CoroutineScope,
    private val actions: StationaryPingActions,
    private val clock: StationaryPingClock = AndroidStationaryPingClock,
    private val alarmScheduler: StationaryPingAlarmScheduler = NoOpStationaryPingAlarmScheduler,
    initialIntervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    private var intervalMs: Long = initialIntervalMs
    private var job: Job? = null
    private var dueAtElapsedMs: Long = 0L
    private var isProviderAvailable: Boolean = true
    private var isDueWhileProviderUnavailable: Boolean = false

    fun onPaused(reason: String, providerAvailable: Boolean) {
        isProviderAvailable = providerAvailable
        if (job?.isActive == true) {
            actions.logEvent(
                "stationary_ping_schedule_kept",
                "reason=$reason dueInMs=${(dueAtElapsedMs - clock.elapsedRealtimeMs()).coerceAtLeast(0L)}"
            )
            return
        }
        isDueWhileProviderUnavailable = false
        dueAtElapsedMs = clock.elapsedRealtimeMs() + intervalMs
        actions.logEvent(
            "stationary_ping_scheduled",
            "reason=$reason intervalMs=$intervalMs providerAvailable=$providerAvailable"
        )
        alarmScheduler.schedule(dueAtElapsedMs)
        job = scope.launch {
            val remaining = dueAtElapsedMs - clock.elapsedRealtimeMs()
            if (remaining > 0L) {
                delay(remaining)
            }
            dispatchIfReady(reason = "interval_elapsed")
        }
    }

    fun onProviderPaused(reason: String) {
        isProviderAvailable = false
        actions.logEvent("stationary_ping_provider_paused", "reason=$reason scheduled=${job != null}")
    }

    fun onProviderRestored(reason: String) {
        isProviderAvailable = true
        actions.logEvent(
            "stationary_ping_provider_restored",
            "reason=$reason dueWhileUnavailable=$isDueWhileProviderUnavailable"
        )
        if (isDueWhileProviderUnavailable) {
            dispatchIfReady(reason = "provider_restored")
            return
        }
        if (job != null && clock.elapsedRealtimeMs() >= dueAtElapsedMs) {
            job?.cancel()
            job = null
            dispatchIfReady(reason = "provider_restored")
        }
    }

    fun onResumed(reason: String) {
        cancel(reason = reason, eventName = "stationary_ping_cancelled")
    }

    fun onStopped(reason: String) {
        cancel(reason = reason, eventName = "stationary_ping_stopped")
    }

    /**
     * Invoked when the wake-guaranteed alarm scheduled by [StationaryPingAlarmScheduler] fires.
     * This is a separate, OS-level-wake-backed path that can deliver the ping even when the
     * in-process coroutine [job] missed its window because the CPU was asleep. Whichever path
     * dispatches first wins; [dispatchIfReady] cancels both.
     */
    fun onAlarmFired(reason: String) {
        dispatchIfReady(reason = reason)
    }

    fun reschedulePausedPing(newIntervalMs: Long, providerAvailable: Boolean, reason: String) {
        intervalMs = newIntervalMs
        val wasScheduled = job != null || dueAtElapsedMs > 0L
        cancel(reason = reason, eventName = "stationary_ping_reschedule_cancelled")
        if (wasScheduled) {
            onPaused(reason = reason, providerAvailable = providerAvailable)
        }
    }

    private fun dispatchIfReady(reason: String) {
        job?.cancel()
        job = null
        alarmScheduler.cancel()
        if (!isProviderAvailable) {
            isDueWhileProviderUnavailable = true
            actions.logEvent("stationary_ping_deferred", "reason=$reason providerAvailable=false")
            return
        }
        isDueWhileProviderUnavailable = false
        actions.logEvent("stationary_ping_due", "reason=$reason")
        actions.requestProbe(reason)
    }

    private fun cancel(reason: String, eventName: String) {
        val hadSchedule = job != null || dueAtElapsedMs > 0L || isDueWhileProviderUnavailable
        job?.cancel()
        job = null
        alarmScheduler.cancel()
        dueAtElapsedMs = 0L
        isDueWhileProviderUnavailable = false
        if (hadSchedule) {
            actions.logEvent(eventName, "reason=$reason")
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 5L * 60_000L
    }
}
