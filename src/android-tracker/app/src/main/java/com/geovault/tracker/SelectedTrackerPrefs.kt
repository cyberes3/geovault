package com.geovault.tracker

import android.content.Context

object SelectedTrackerPrefs {
    private const val PREFS_NAME = "geovault_prefs"
    private const val KEY_SELECTED_TRACKER_ID = "selected_tracker_id"
    private const val KEY_SELECTED_TRACKER_NAME = "selected_tracker_name"

    fun selectedTrackerId(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_TRACKER_ID, "")?.trim().orEmpty()
    }

    fun selectedTrackerName(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_TRACKER_NAME, "")?.trim().orEmpty()
    }

    fun setSelectedTracker(context: Context, trackerId: String, trackerName: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_SELECTED_TRACKER_ID, trackerId.trim())
            .putString(KEY_SELECTED_TRACKER_NAME, trackerName?.trim().orEmpty())
            .apply()
    }

    fun clearSelectedTracker(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_SELECTED_TRACKER_ID)
            .remove(KEY_SELECTED_TRACKER_NAME)
            .apply()
    }

    fun updateSelectedTrackerName(context: Context, trackerName: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_SELECTED_TRACKER_NAME, trackerName?.trim().orEmpty())
            .apply()
    }
}
