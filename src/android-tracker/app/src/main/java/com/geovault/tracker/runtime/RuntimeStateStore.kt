package com.geovault.tracker.runtime

import android.content.Context
import org.json.JSONObject

class RuntimeStateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    fun read(): RuntimeState {
        val raw = prefs.getString(KEY_STATE_JSON, null) ?: return RuntimeState()
        return try {
            val obj = JSONObject(raw)
            RuntimeState(
                lifecycleState = RuntimeLifecycleState.valueOf(
                    obj.optString(KEY_LIFECYCLE_STATE, RuntimeLifecycleState.IDLE.name)
                ),
                shouldBeRunning = obj.optBoolean(KEY_SHOULD_BE_RUNNING, false),
                lastIntentionalStop = obj.optBoolean(KEY_LAST_INTENTIONAL_STOP, false),
                lastFailure = parseFailure(obj.optJSONObject(KEY_LAST_FAILURE)),
                lastStartTrigger = parseTrigger(obj.optString(KEY_LAST_START_TRIGGER, "")),
                lastTransitionAtMs = obj.optLong(KEY_LAST_TRANSITION_AT_MS, 0L),
                lastHeartbeatAtMs = obj.optLong(KEY_LAST_HEARTBEAT_AT_MS, 0L)
            )
        } catch (_: Exception) {
            RuntimeState()
        }
    }

    fun update(transform: (RuntimeState) -> RuntimeState): RuntimeState {
        synchronized(lock) {
            val next = transform(read())
            prefs.edit().putString(KEY_STATE_JSON, toJson(next).toString()).apply()
            return next
        }
    }

    private fun toJson(state: RuntimeState): JSONObject {
        return JSONObject().apply {
            put(KEY_LIFECYCLE_STATE, state.lifecycleState.name)
            put(KEY_SHOULD_BE_RUNNING, state.shouldBeRunning)
            put(KEY_LAST_INTENTIONAL_STOP, state.lastIntentionalStop)
            put(KEY_LAST_TRANSITION_AT_MS, state.lastTransitionAtMs)
            put(KEY_LAST_HEARTBEAT_AT_MS, state.lastHeartbeatAtMs)
            put(KEY_LAST_START_TRIGGER, state.lastStartTrigger?.name ?: "")
            val failureObj = state.lastFailure?.let {
                JSONObject().apply {
                    put(KEY_FAILURE_CLASS, it.clazz.name)
                    put(KEY_FAILURE_REASON, it.reason)
                }
            }
            put(KEY_LAST_FAILURE, failureObj ?: JSONObject())
        }
    }

    private fun parseFailure(obj: JSONObject?): RuntimeFailure? {
        if (obj == null || obj.length() == 0) return null
        return try {
            RuntimeFailure(
                clazz = RuntimeFailureClass.valueOf(obj.optString(KEY_FAILURE_CLASS, RuntimeFailureClass.NONE.name)),
                reason = obj.optString(KEY_FAILURE_REASON, "")
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseTrigger(raw: String): RuntimeTrigger? {
        if (raw.isBlank()) return null
        return try {
            RuntimeTrigger.valueOf(raw)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val PREFS_NAME = "tracking_runtime_state_v2"
        private const val KEY_STATE_JSON = "state_json"
        private const val KEY_LIFECYCLE_STATE = "lifecycle_state"
        private const val KEY_SHOULD_BE_RUNNING = "should_be_running"
        private const val KEY_LAST_INTENTIONAL_STOP = "last_intentional_stop"
        private const val KEY_LAST_FAILURE = "last_failure"
        private const val KEY_FAILURE_CLASS = "class"
        private const val KEY_FAILURE_REASON = "reason"
        private const val KEY_LAST_START_TRIGGER = "last_start_trigger"
        private const val KEY_LAST_TRANSITION_AT_MS = "last_transition_at_ms"
        private const val KEY_LAST_HEARTBEAT_AT_MS = "last_heartbeat_at_ms"
    }
}
