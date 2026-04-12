package com.geovault.tracker.presentation

object TrackerMapMarkerStylePolicy {
    fun normalizedColorOrDefault(
        rawColor: String?,
        fallbackColorHex: String = TrackerMapIconIds.DEFAULT_COLOR_HEX,
    ): String {
        if (rawColor.isNullOrBlank()) return fallbackColorHex
        val normalized = rawColor.trim()
        return if (normalized.startsWith("#")) normalized else "#$normalized"
    }

    fun singleTrackerIconId(
        trackerColorById: Map<String, String>,
        displayedTrackerId: String,
        selectedTrackerId: String,
        fallbackColorHex: String = TrackerMapIconIds.DEFAULT_COLOR_HEX,
    ): String {
        val activeTrackerId = displayedTrackerId.trim().ifBlank { selectedTrackerId.trim() }
        val color = normalizedColorOrDefault(trackerColorById[activeTrackerId], fallbackColorHex)
        return TrackerMapIconIds.selectedForColor(color)
    }

    fun multiTrackerIconId(
        trackerId: String,
        trackerColorById: Map<String, String>,
        selectedMapTrackerId: String?,
        fallbackColorHex: String = TrackerMapIconIds.DEFAULT_COLOR_HEX,
    ): String {
        val normalizedTrackerId = trackerId.trim()
        val selectedId = selectedMapTrackerId?.trim().orEmpty()
        val color = normalizedColorOrDefault(trackerColorById[normalizedTrackerId], fallbackColorHex)
        return if (selectedId.isNotBlank() && selectedId == normalizedTrackerId) {
            TrackerMapIconIds.selectedForColor(color)
        } else {
            TrackerMapIconIds.simpleForColor(color)
        }
    }
}
