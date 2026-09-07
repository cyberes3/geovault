package com.geovault.tracker.data

import com.geovault.tracker.Tracker

interface TrackerDetailRepository {
    suspend fun loadTrackerMetadata(
        trackerId: String,
        forceRefresh: Boolean = false
    ): Tracker
    suspend fun refreshTrackers()
    fun clearSelectedTrackerCaches()
}
