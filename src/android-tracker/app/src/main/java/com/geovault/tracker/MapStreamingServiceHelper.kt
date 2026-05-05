package com.geovault.tracker

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

internal sealed class MapStreamingStartResult {
    data class Started(val trackerIds: Set<String>) : MapStreamingStartResult()
    data class Failed(val reason: String) : MapStreamingStartResult()
}

internal sealed class MapStreamingStopResult {
    data object Stopped : MapStreamingStopResult()
    data class Failed(val reason: String) : MapStreamingStopResult()
}

internal object MapStreamingServiceHelper {
    private const val PREFS_NAME = "live_track_streaming_targets"
    private const val KEY_TRACKER_IDS = "tracker_ids"
    private const val KEY_TRACKER_NAME = "tracker_name"

    /**
     * Prefer a normal service start; if Android rejects background start, escalate to
     * startForegroundService so streaming can still bootstrap.
     */
    fun startStreaming(context: Context, trackerIds: Set<String>, trackerName: String? = null): MapStreamingStartResult {
        val cleanedIds = trackerIds.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
        if (cleanedIds.isEmpty()) {
            return MapStreamingStartResult.Failed("No live streaming trackers were selected")
        }
        val app = context.applicationContext
        persistTargets(app, cleanedIds, trackerName)
        val intent = Intent(app, LiveTrackStreamingService::class.java).apply {
            action = LiveTrackStreamingService.ACTION_START
            putStringArrayListExtra(LiveTrackStreamingService.EXTRA_TRACKER_IDS, ArrayList(cleanedIds))
            if (cleanedIds.size == 1) {
                putExtra(LiveTrackStreamingService.EXTRA_TRACKER_ID, cleanedIds.first())
            }
            putExtra(LiveTrackStreamingService.EXTRA_TRACKER_NAME, trackerName)
        }
        try {
            app.startService(intent)
        } catch (e: IllegalStateException) {
            Log.w("MapStreamingServiceHelper", "startService rejected; escalating to FGS start", e)
            runCatching { ContextCompat.startForegroundService(app, intent) }
                .exceptionOrNull()
                ?.let { inner ->
                    Log.e("MapStreamingServiceHelper", "FGS start also failed", inner)
                    clearPersistedTargets(app)
                    return MapStreamingStartResult.Failed(
                        inner.message ?: "Unable to start live streaming service"
                    )
                }
        }
        return MapStreamingStartResult.Started(cleanedIds)
    }

    fun stopStreaming(context: Context): MapStreamingStopResult {
        val app = context.applicationContext
        clearPersistedTargets(app)
        val intent = Intent(app, LiveTrackStreamingService::class.java).apply {
            action = LiveTrackStreamingService.ACTION_STOP
        }
        try {
            app.startService(intent)
        } catch (e: IllegalStateException) {
            Log.w("MapStreamingServiceHelper", "stopService command rejected; escalating to FGS start", e)
            runCatching { ContextCompat.startForegroundService(app, intent) }
                .exceptionOrNull()
                ?.let { inner ->
                    Log.e("MapStreamingServiceHelper", "FGS stop command also failed", inner)
                    return MapStreamingStopResult.Failed(
                        inner.message ?: "Unable to stop live streaming service"
                    )
                }
        }
        return MapStreamingStopResult.Stopped
    }

    fun persistedTargets(context: Context): Pair<Set<String>, String?> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ids = prefs.getStringSet(KEY_TRACKER_IDS, emptySet()).orEmpty()
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            .toSet()
        val name = prefs.getString(KEY_TRACKER_NAME, null)?.trim()?.ifBlank { null }
        return ids to name
    }

    private fun persistTargets(context: Context, trackerIds: Set<String>, trackerName: String?) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_TRACKER_IDS, trackerIds)
            .putString(KEY_TRACKER_NAME, trackerName?.trim()?.ifBlank { null })
            .apply()
    }

    /** Clears persisted targets without sending ACTION_STOP (used when subscribe intent is invalidated before WS comes up). */
    internal fun clearPersistedStreamingTargets(context: Context) {
        clearPersistedTargets(context.applicationContext)
    }

    private fun clearPersistedTargets(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
