package com.geovault.tracker.fragments.map

import android.content.Context

internal object MapDefaultTrackerPrefs {
    private const val PREFS_NAME = "geovault_prefs"
    private const val KEY_SELECTED_TRACKER_ID = "selected_tracker_id"
    private const val KEY_SELECTED_TRACKER_NAME = "selected_tracker_name"

    fun defaultTrackerId(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_TRACKER_ID, "") ?: ""
    }

    fun defaultTrackerName(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_TRACKER_NAME, "") ?: ""
    }
}
