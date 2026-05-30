package com.geovault.tracker.location

import android.os.SystemClock
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

class StationaryPingController(
    private val scope: CoroutineScope,
    private val actions: StationaryPingActions,
    private val clock: StationaryPingClock = AndroidStationaryPingClock,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    private var job: Job? = null
    private var dueAtElapsedMs: Long = 0L
    private var isProviderAvailable: Boolean = true
    private var isDueWhileProviderUnavailable: Boolean = false

    fun onPaused(
        reason: String,
        providerAvailable: Boolean,
        dueInMs: Long = intervalMs,
    ) {
        isProviderAvailable = providerAvailable
        if (job?.isActive == true && clock.elapsedRealtimeMs() >= dueAtElapsedMs) {
            job?.cancel()
            dispatchIfReady(reason = reason)
            return
        }
        if (job?.isActive == true) {
            actions.logEvent(
                "stationary_ping_schedule_kept",
                "reason=$reason dueInMs=${(dueAtElapsedMs - clock.elapsedRealtimeMs()).coerceAtLeast(0L)}"
            )
            return
        }
        isDueWhileProviderUnavailable = false
        dueAtElapsedMs = clock.elapsedRealtimeMs() + dueInMs.coerceAtLeast(0L)
        actions.logEvent(
            "stationary_ping_scheduled",
            "reason=$reason dueInMs=${dueInMs.coerceAtLeast(0L)} providerAvailable=$providerAvailable"
        )
        job = scope.launch {
            val remaining = dueAtElapsedMs - clock.elapsedRealtimeMs()
            if (remaining > 0L) {
                delay(remaining)
            }
            dispatchIfReady(reason = "interval_elapsed")
        }
    }

    fun reconcilePausedState(
        reason: String,
        providerAvailable: Boolean,
        dueInMs: Long,
    ) {
        isProviderAvailable = providerAvailable
        if (isDueWhileProviderUnavailable && providerAvailable) {
            dispatchIfReady(reason = reason)
            return
        }
        if (job?.isActive == true) {
            if (clock.elapsedRealtimeMs() >= dueAtElapsedMs) {
                job?.cancel()
                dispatchIfReady(reason = reason)
            }
            return
        }
        onPaused(
            reason = reason,
            providerAvailable = providerAvailable,
            dueInMs = dueInMs,
        )
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

    private fun dispatchIfReady(reason: String) {
        job = null
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
