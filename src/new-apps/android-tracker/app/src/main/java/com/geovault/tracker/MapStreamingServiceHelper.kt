package com.geovault.tracker

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.geovault.tracker.presentation.TrackerMapDisplayMode

internal object MapStreamingServiceHelper {
    fun startStreaming(context: Context, trackerIds: Set<String>, trackerName: String? = null): Set<String>? {
        val cleanedIds = trackerIds.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
        if (cleanedIds.isEmpty()) return null
        val intent = Intent(context, LiveTrackStreamingService::class.java).apply {
            action = LiveTrackStreamingService.ACTION_START
            putStringArrayListExtra(LiveTrackStreamingService.EXTRA_TRACKER_IDS, ArrayList(cleanedIds))
            if (cleanedIds.size == 1) {
                putExtra(LiveTrackStreamingService.EXTRA_TRACKER_ID, cleanedIds.first())
            }
            putExtra(LiveTrackStreamingService.EXTRA_TRACKER_NAME, trackerName)
        }
        ContextCompat.startForegroundService(context, intent)
        return cleanedIds
    }

    fun stopStreaming(context: Context) {
        context.startService(
            Intent(context, LiveTrackStreamingService::class.java).apply {
                action = LiveTrackStreamingService.ACTION_STOP
            }
        )
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
