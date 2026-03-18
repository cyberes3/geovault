package com.geovault.tracker

import android.content.Context

object TrackerGeometryRepository {
    fun getTrackerGeometry(
        context: Context,
        id: String,
        callback: (Tracker?) -> Unit
    ) {
        TrackerRepository.getTrackerGeometry(context, id, callback)
    }

    fun getTrackersGeometry(
        context: Context,
        trackerIds: List<String>,
        allData: Boolean = true,
        callback: (List<Tracker>?) -> Unit
    ) {
        TrackerRepository.getTrackersGeometry(context, trackerIds, allData, callback)
    }

    fun clearGeometryCache() {
        TrackerRepository.clearGeometryCache()
    }
}

