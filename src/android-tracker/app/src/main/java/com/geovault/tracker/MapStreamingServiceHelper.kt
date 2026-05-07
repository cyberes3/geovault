package com.geovault.tracker

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.geovault.tracker.policy.StreamingTargetPolicy

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
    fun startStreaming(
        context: Context,
        trackerIds: Set<String>,
        trackerName: String? = null,
    ): MapStreamingStartResult {
        val cleanedIds = sanitizeStreamingTargets(trackerIds)
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

    /**
     * STOP-PREFS-ORDER: persisted streaming targets are cleared by the service inside
     * [LiveTrackStreamingService.ACTION_STOP], not here. If we cleared them up-front and then the
     * stop intent failed to deliver (e.g. FGS background-start rejection), the next process
     * start would see empty prefs even though the streaming session is still alive, so it would
     * fail to restore the foreground notification. Letting the service authoritatively clear
     * them keeps prefs and runtime state consistent.
     */
    fun stopStreaming(context: Context): MapStreamingStopResult {
        val app = context.applicationContext
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
        val rawIds = prefs.getStringSet(KEY_TRACKER_IDS, emptySet()).orEmpty()
        val ids = sanitizeStreamingTargets(rawIds)
        val name = prefs.getString(KEY_TRACKER_NAME, null)?.trim()?.ifBlank { null }
        if (ids != rawIds.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()) {
            if (ids.isEmpty()) {
                clearPersistedTargets(context.applicationContext)
            } else {
                persistTargets(context.applicationContext, ids, name)
            }
        }
        return ids to name
    }

    internal fun sanitizeStreamingTargets(trackerIds: Collection<String>): Set<String> {
        return StreamingTargetPolicy.normalizeTrackerIds(trackerIds)
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
