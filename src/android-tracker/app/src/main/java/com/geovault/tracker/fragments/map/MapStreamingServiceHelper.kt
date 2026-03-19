package com.geovault.tracker.fragments.map

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.geovault.tracker.LiveTrackStreamingService

internal object MapStreamingServiceHelper {
    /**
     * Starts live track streaming for the given tracker IDs.
     * @return The cleaned set of IDs that were used, or null if empty (no service started).
     */
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
        val intent = Intent(context, LiveTrackStreamingService::class.java).apply {
            action = LiveTrackStreamingService.ACTION_STOP
        }
        context.startService(intent)
    }

    /**
     * Updates streaming for the displayed tracker: stream whenever map is in single-tracker
     * context and a tracker id is available.
     */
    fun updateStreamingForDisplayedTracker(
        displayedTrackerId: String?,
        displayedTrackerName: String?,
        selectedTrackerId: String?,
        mapViewContext: MapViewContext,
        startStreaming: (Set<String>, String?) -> Unit,
        stopStreaming: () -> Unit
    ) {
        if (mapViewContext == MapViewContext.GROUP) {
            stopStreaming()
            return
        }
        val id = displayedTrackerId ?: return
        if (!selectedTrackerId.isNullOrEmpty() && id == selectedTrackerId) {
            stopStreaming()
            return
        }
        startStreaming(setOf(id), displayedTrackerName)
    }
}
