package com.geovault.tracker

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.geovault.tracker.presentation.TrackerMapDisplayMode

internal object MapStreamingServiceHelper {
    private const val PREFS_NAME = "live_track_streaming_targets"
    private const val KEY_TRACKER_IDS = "tracker_ids"
    private const val KEY_TRACKER_NAME = "tracker_name"

    /**
     * Prefer a normal service start; if Android rejects background start, escalate to
     * startForegroundService so streaming can still bootstrap.
     */
    fun startStreaming(context: Context, trackerIds: Set<String>, trackerName: String? = null): Set<String>? {
        val cleanedIds = trackerIds.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
        if (cleanedIds.isEmpty()) return null
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
                    return null
                }
        }
        return cleanedIds
    }

    fun stopStreaming(context: Context) {
        val app = context.applicationContext
        clearPersistedTargets(app)
        app.startService(
            Intent(app, LiveTrackStreamingService::class.java).apply {
                action = LiveTrackStreamingService.ACTION_STOP
            }
        )
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

    private fun clearPersistedTargets(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    fun updateStreamingForDisplayedTracker(
        displayedTrackerId: String?,
        displayedTrackerName: String?,
        selectedTrackerId: String?,
        mapMode: TrackerMapDisplayMode,
        startStreaming: (Set<String>, String?) -> Unit,
        stopStreaming: () -> Unit
    ) {
        if (mapMode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
            stopStreaming()
            return
        }
        val id = displayedTrackerId?.trim().orEmpty()
        if (id.isBlank()) {
            // No-op when single-context tracker id is not yet resolved.
            return
        }
        if (!selectedTrackerId.isNullOrBlank() && id == selectedTrackerId) {
            stopStreaming()
            return
        }
        startStreaming(setOf(id), displayedTrackerName)
    }
}
