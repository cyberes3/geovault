package com.geovault.tracker.fragments.map

import android.os.Bundle

internal data class MapSavedState(
    val lockMode: MapLockMode = MapLockMode.NONE,
    val lockTargetLat: Double? = null,
    val lockTargetLon: Double? = null,
    val lockNeedsInitialZoom: Boolean = false,
    val showMyLocationEnabled: Boolean = false,
    val displayedTrackerId: String? = null,
    val displayedTrackerName: String? = null,
    val displayedGroupName: String? = null,
    val showAllTrackers: Boolean = false,
    val mapViewContext: MapViewContext = MapViewContext.SINGLE_TRACKER
) {
    fun writeTo(outState: Bundle) {
        outState.putString(KEY_LOCK_MODE, lockMode.name)
        outState.putBoolean(KEY_LOCK_INITIAL_ZOOM, lockNeedsInitialZoom)
        if (lockTargetLat != null) outState.putDouble(KEY_LOCK_TARGET_LAT, lockTargetLat)
        if (lockTargetLon != null) outState.putDouble(KEY_LOCK_TARGET_LON, lockTargetLon)
        outState.putBoolean(KEY_SHOW_MY_LOCATION, showMyLocationEnabled)
        outState.putString(KEY_DISPLAYED_TRACKER_ID, displayedTrackerId)
        outState.putString(KEY_DISPLAYED_TRACKER_NAME, displayedTrackerName)
        outState.putString(KEY_DISPLAYED_GROUP_NAME, displayedGroupName)
        outState.putBoolean(KEY_SHOW_ALL_TRACKERS, showAllTrackers)
        outState.putString(KEY_MAP_VIEW_CONTEXT, mapViewContext.name)
    }

    companion object {
        private const val KEY_LOCK_MODE = "map_lock_mode"
        private const val KEY_LOCK_INITIAL_ZOOM = "map_lock_initial_zoom"
        private const val KEY_LOCK_TARGET_LAT = "lock_target_lat"
        private const val KEY_LOCK_TARGET_LON = "lock_target_lon"
        private const val KEY_SHOW_MY_LOCATION = "show_my_location"
        private const val KEY_DISPLAYED_TRACKER_ID = "displayed_tracker_id"
        private const val KEY_DISPLAYED_TRACKER_NAME = "displayed_tracker_name"
        private const val KEY_DISPLAYED_GROUP_NAME = "displayed_group_name"
        private const val KEY_SHOW_ALL_TRACKERS = "show_all_trackers"
        private const val KEY_MAP_VIEW_CONTEXT = "map_view_context"

        fun readFrom(bundle: Bundle?): MapSavedState {
            if (bundle == null) return MapSavedState()
            val lockMode = bundle.getString(KEY_LOCK_MODE)
                ?.let { runCatching { MapLockMode.valueOf(it) }.getOrNull() }
                ?: MapLockMode.NONE
            val context = bundle.getString(KEY_MAP_VIEW_CONTEXT)
                ?.let { runCatching { MapViewContext.valueOf(it) }.getOrNull() }
                ?: MapViewContext.SINGLE_TRACKER
            val lockTargetLat = if (bundle.containsKey(KEY_LOCK_TARGET_LAT)) {
                bundle.getDouble(KEY_LOCK_TARGET_LAT)
            } else {
                null
            }
            val lockTargetLon = if (bundle.containsKey(KEY_LOCK_TARGET_LON)) {
                bundle.getDouble(KEY_LOCK_TARGET_LON)
            } else {
                null
            }
            return MapSavedState(
                lockMode = lockMode,
                lockTargetLat = lockTargetLat,
                lockTargetLon = lockTargetLon,
                lockNeedsInitialZoom = bundle.getBoolean(KEY_LOCK_INITIAL_ZOOM, false),
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

