package com.geovault.tracker.presentation

import com.geovault.tracker.Group
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.Tracker

data class TrackerMapRenderMetadataSnapshot(
    val fingerprint: String,
    val recentDataWindowByTracker: Map<String, String?>,
)

data class TrackerMapRenderMetadataDiff(
    val fingerprintChanged: Boolean,
    val recentDataWindowChangedTrackerIds: Set<String>,
)

/**
 * Canonical map-render metadata fingerprint. Only fields that affect trail projection,
 * cosmetics, or streaming target sets — never geometry or `updated_at` (those advance on
 * every live point and would spam reloads).
 */
object TrackerMapRenderMetadataPolicy {

    fun capture(
        trackers: List<Tracker>,
        groups: List<Group>,
        mapVisibility: MapVisibilityResponse?,
    ): TrackerMapRenderMetadataSnapshot {
        val recentDataWindowByTracker = trackers.associate { tracker ->
            tracker.id to recentDataWindowKey(tracker)
        }
        val trackerFingerprint = trackers.joinToString(separator = "|") { tracker ->
            val window = recentDataWindowByTracker[tracker.id].orEmpty()
            "${tracker.id}:${tracker.name}:${tracker.color}:$window"
        }
        val groupFingerprint = groups.joinToString(separator = "|") { group ->
            val memberIds = group.track_ids.orEmpty().sorted().joinToString(",")
            "${group.id}:$memberIds"
        }
        val visibilityFingerprint = if (mapVisibility == null) {
            "none"
        } else {
            "${mapVisibility.hidden_group_ids.orEmpty().sorted()}|" +
                mapVisibility.hidden_track_ids.orEmpty().sorted()
        }
        return TrackerMapRenderMetadataSnapshot(
            fingerprint = "$trackerFingerprint#$groupFingerprint#$visibilityFingerprint",
            recentDataWindowByTracker = recentDataWindowByTracker,
        )
    }

    fun diff(
        previous: TrackerMapRenderMetadataSnapshot?,
        next: TrackerMapRenderMetadataSnapshot,
    ): TrackerMapRenderMetadataDiff {
        if (previous == null) {
            return TrackerMapRenderMetadataDiff(
                fingerprintChanged = true,
                recentDataWindowChangedTrackerIds = emptySet(),
            )
        }
        val windowChanges = mutableSetOf<String>()
        val allIds = previous.recentDataWindowByTracker.keys + next.recentDataWindowByTracker.keys
        for (trackerId in allIds) {
            val oldWindow = previous.recentDataWindowByTracker[trackerId]
            val newWindow = next.recentDataWindowByTracker[trackerId]
            if (oldWindow != newWindow) {
                windowChanges += trackerId
            }
        }
        return TrackerMapRenderMetadataDiff(
            fingerprintChanged = previous.fingerprint != next.fingerprint,
            recentDataWindowChangedTrackerIds = windowChanges,
        )
    }

    private fun recentDataWindowKey(tracker: Tracker): String? {
        val raw = tracker.settings?.get("recent_data_window") ?: return null
        return when (raw) {
            is String -> raw.trim().ifEmpty { null }
            else -> raw.toString().trim().ifEmpty { null }
        }
    }
}
