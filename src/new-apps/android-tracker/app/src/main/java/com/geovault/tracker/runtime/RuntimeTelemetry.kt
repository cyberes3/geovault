package com.geovault.tracker.runtime

import android.content.Context
import android.util.Log

class RuntimeTelemetry(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun event(name: String, details: String) {
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
        Log.i(TAG, "$name $details")
    }

    fun decision(name: String, details: String) {
        event(name = "decision:$name", details = details)
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
        Log.i(TAG, "dump reason=$reason entries=${entries.size}")
        entries.forEachIndexed { index, entry ->
            Log.i(TAG, "entry[${index + 1}/${entries.size}] $entry")
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
