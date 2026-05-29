package com.geovault.tracker.runtime

import android.content.Context
import com.geovault.common.logging.CaptureLogThrottle
import com.geovault.common.logging.GeoVaultCaptureLog

class RuntimeTelemetry(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
            val line = "${System.currentTimeMillis()}|$name|$details"
            synchronized(this) {
                val entries = prefs.getString(KEY_RING, "")
                    .orEmpty()
                    .lineSequence()
                    .filter { it.isNotBlank() }
                    .toMutableList()
                entries.add(line)
                val trimmed = if (entries.size > MAX_ENTRIES) entries.takeLast(MAX_ENTRIES) else entries
                prefs.edit().putString(KEY_RING, trimmed.joinToString("\n")).apply()
            }
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

    fun dumpToLogcat(reason: String) {
        val entries = prefs.getString(KEY_RING, "")
            .orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()
        GeoVaultCaptureLog.i(TAG, "dump reason=$reason entries=${entries.size}")
        entries.forEachIndexed { index, entry ->
            GeoVaultCaptureLog.i(TAG, "entry[${index + 1}/${entries.size}] $entry")
        }
    }

    companion object {
        private const val TAG = "TrackingRuntimeTelemetry"
        private const val PREFS_NAME = "tracking_runtime_telemetry_v2"
        private const val KEY_RING = "ring"
        private const val MAX_ENTRIES = 400

        private fun summarize(state: RuntimeState): String {
            return "lifecycle=${state.lifecycleState},desired=${state.shouldBeRunning},intentionalStop=${state.lastIntentionalStop},lastFailure=${state.lastFailure?.reason ?: "none"},lastTrigger=${state.lastStartTrigger ?: "none"},lastHeartbeat=${state.lastHeartbeatAtMs}"
        }
    }
}
