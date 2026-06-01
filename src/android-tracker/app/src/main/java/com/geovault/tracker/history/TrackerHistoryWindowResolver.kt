package com.geovault.tracker.history

import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.Tracker

/**
 * Canonical history keys always use the tracker's **settings** `recent_data_window`.
 * Server `geometry_status.window` describes how geometry was filtered on the wire and may
 * differ when cache is stale; storing under a different key would make render lookups miss.
 */
object TrackerHistoryWindowResolver {
    private const val TAG = "TrackerHistoryWindowResolver"

    fun fromTracker(tracker: Tracker?): TrackerHistoryWindow {
        val key = tracker?.settings?.get("recent_data_window") as? String
        return TrackerHistoryWindow(key ?: TrackerHistoryWindow.KEY_ALL)
    }

    fun fromSettingsKey(key: String?): TrackerHistoryWindow {
        return TrackerHistoryWindow(key ?: TrackerHistoryWindow.KEY_ALL)
    }

    fun logStatusWindowMismatchIfNeeded(
        trackerId: String,
        settingsWindow: TrackerHistoryWindow,
        statusWindowKey: String?,
    ) {
        val statusKey = statusWindowKey?.trim()?.lowercase().orEmpty()
        if (statusKey.isEmpty()) return
        if (statusKey == settingsWindow.normalizedKey) return
        GeoVaultCaptureLog.w(
            TAG,
            "map_update history_window_mismatch tracker=$trackerId settings=${settingsWindow.normalizedKey} " +
                "geometry_status=$statusKey action=use_settings_window",
        )
    }
}
