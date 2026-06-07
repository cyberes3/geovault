package com.geovault.tracker.runtime

import android.content.Context
import com.geovault.common.logging.CaptureLogThrottle
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.positioning.time.PositioningClock
import com.geovault.tracker.positioning.time.SystemPositioningClock

class RuntimeTelemetry(
    context: Context,
    private val clock: PositioningClock = SystemPositioningClock,
) {
    private val store = RuntimeTelemetryStore(context.applicationContext)

    fun event(
        name: String,
        details: String,
        persistRing: Boolean = true,
        minCaptureLogIntervalMs: Long = 0L,
    ) {
        if (minCaptureLogIntervalMs > 0L &&
            !CaptureLogThrottle.shouldLogInterval("runtime_event:$name", minCaptureLogIntervalMs)
        ) {
            return
        }
        if (persistRing) {
            store.insert(wallTimeMs = clock.wallTimeMs(), name = name, details = details)
        }
        GeoVaultCaptureLog.i(TAG, "$name $details")
    }

    fun decision(name: String, details: String, minCaptureLogIntervalMs: Long = 0L) {
        event(
            name = "decision:$name",
            details = details,
            minCaptureLogIntervalMs = minCaptureLogIntervalMs,
        )
    }

    fun transition(name: String, fromState: RuntimeState, toState: RuntimeState) {
        event(
            name = "transition:$name",
            details = "from=${summarize(fromState)} to=${summarize(toState)}"
        )
    }

    fun readAllLines(): List<String> = store.readAllLines()

    fun clear() = store.clear()

    fun dumpToLogcat(reason: String) {
        val entries = store.readAllLines()
        GeoVaultCaptureLog.i(TAG, "dump reason=$reason entries=${entries.size}")
        entries.forEachIndexed { index, entry ->
            GeoVaultCaptureLog.i(TAG, "entry[${index + 1}/${entries.size}] $entry")
        }
    }

    companion object {
        private const val TAG = "TrackingRuntimeTelemetry"

        private fun summarize(state: RuntimeState): String {
            return "lifecycle=${state.lifecycleState},desired=${state.shouldBeRunning},intentionalStop=${state.lastIntentionalStop},lastFailure=${state.lastFailure?.reason ?: "none"},lastTrigger=${state.lastStartTrigger ?: "none"},lastHeartbeat=${state.lastHeartbeatAtMs}"
        }
    }
}
