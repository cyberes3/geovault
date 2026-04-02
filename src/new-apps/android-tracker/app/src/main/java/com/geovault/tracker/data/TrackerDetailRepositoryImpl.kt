package com.geovault.tracker.data

import com.geovault.tracker.AppError
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker

class TrackerDetailRepositoryImpl(
    private val trackerManagementRepository: TrackerManagementRepository
) : TrackerDetailRepository {
    override suspend fun loadTrackerMetadata(
        trackerId: String,
        forceRefresh: Boolean
    ): RepositoryResult<Tracker> {
        return when (val result = trackerManagementRepository.loadTracker(trackerId)) {
            is RepositoryResult.Success -> result
            is RepositoryResult.Failure -> {
                if (result.error == AppError.NotFound) {
                    result
                } else {
                    RepositoryResult.Failure(result.error)
                }
            }
        }
    }

    override suspend fun refreshTrackers() {
        trackerManagementRepository.loadTrackers(forceRefresh = true)
    }

    override fun clearSelectedTrackerCaches() {
        trackerManagementRepository.clearSelectedTrackerCaches()
    }
}
