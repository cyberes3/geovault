package com.geovault.tracker.fragments.map

import android.os.Bundle

internal data class MapSavedState(
    val followLockEnabled: Boolean = false,
    val showMyLocationEnabled: Boolean = false,
    val displayedTrackerId: String? = null,
    val displayedTrackerName: String? = null,
    val displayedGroupName: String? = null,
    val showAllTrackers: Boolean = false,
    val mapViewContext: MapViewContext = MapViewContext.SINGLE_TRACKER
) {
    fun writeTo(outState: Bundle) {
        outState.putBoolean(KEY_FOLLOW_LOCK, followLockEnabled)
        outState.putBoolean(KEY_SHOW_MY_LOCATION, showMyLocationEnabled)
        outState.putString(KEY_DISPLAYED_TRACKER_ID, displayedTrackerId)
        outState.putString(KEY_DISPLAYED_TRACKER_NAME, displayedTrackerName)
        outState.putString(KEY_DISPLAYED_GROUP_NAME, displayedGroupName)
        outState.putBoolean(KEY_SHOW_ALL_TRACKERS, showAllTrackers)
        outState.putString(KEY_MAP_VIEW_CONTEXT, mapViewContext.name)
    }

    companion object {
        private const val KEY_FOLLOW_LOCK = "follow_lock"
        private const val KEY_SHOW_MY_LOCATION = "show_my_location"
        private const val KEY_DISPLAYED_TRACKER_ID = "displayed_tracker_id"
        private const val KEY_DISPLAYED_TRACKER_NAME = "displayed_tracker_name"
        private const val KEY_DISPLAYED_GROUP_NAME = "displayed_group_name"
        private const val KEY_SHOW_ALL_TRACKERS = "show_all_trackers"
        private const val KEY_MAP_VIEW_CONTEXT = "map_view_context"

        fun readFrom(bundle: Bundle?): MapSavedState {
            if (bundle == null) return MapSavedState()
            val context = bundle.getString(KEY_MAP_VIEW_CONTEXT)
                ?.let { runCatching { MapViewContext.valueOf(it) }.getOrNull() }
                ?: MapViewContext.SINGLE_TRACKER
            return MapSavedState(
                followLockEnabled = bundle.getBoolean(KEY_FOLLOW_LOCK, false),
                showMyLocationEnabled = bundle.getBoolean(KEY_SHOW_MY_LOCATION, false),
                displayedTrackerId = bundle.getString(KEY_DISPLAYED_TRACKER_ID),
                displayedTrackerName = bundle.getString(KEY_DISPLAYED_TRACKER_NAME),
                displayedGroupName = bundle.getString(KEY_DISPLAYED_GROUP_NAME),
                showAllTrackers = bundle.getBoolean(KEY_SHOW_ALL_TRACKERS, false),
                mapViewContext = context
            )
        }
    }
}

