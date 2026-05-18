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
            val hidden = ownerHiddenFingerprint(tracker)
            "${tracker.id}:${tracker.name}:${tracker.color}:$window:$hidden"
        }
        val groupFingerprint = groups.joinToString(separator = "|") { group ->
            val memberIds = group.track_ids.orEmpty().sorted().joinToString(",")
            val hidden = if (group.hidden == true) "1" else "0"
            "${group.id}:$memberIds:$hidden"
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

    private fun ownerHiddenFingerprint(tracker: Tracker): String {
        if (!tracker.isOwner()) return "-"
        return if (tracker.settingBoolean("hidden") == true) "1" else "0"
    }

    private fun Tracker.settingBoolean(key: String): Boolean? {
        val raw = settings?.get(key) ?: return null
        return when (raw) {
            is Boolean -> raw
            is String -> raw.equals("true", ignoreCase = true)
            is Number -> raw.toInt() != 0
            else -> null
        }
    }
}
