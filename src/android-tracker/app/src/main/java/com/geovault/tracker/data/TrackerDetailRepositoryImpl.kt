package com.geovault.tracker.data

import com.geovault.tracker.Tracker

class TrackerDetailRepositoryImpl(
    private val trackerManagementRepository: TrackerManagementRepository
) : TrackerDetailRepository {
    override suspend fun loadTrackerMetadata(
        trackerId: String,
        forceRefresh: Boolean
    ): Tracker {
        return trackerManagementRepository.loadTracker(trackerId)
    }

    override suspend fun refreshTrackers() {
        trackerManagementRepository.loadTrackers(forceRefresh = true)
    }

    override fun clearSelectedTrackerCaches() {
        trackerManagementRepository.clearSelectedTrackerCaches()
    }
}
