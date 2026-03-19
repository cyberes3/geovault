package com.geovault.tracker.data

import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import javax.inject.Inject

class TrackerRepositoryTrackerListRepository @Inject constructor(
    private val trackerManagementRepository: TrackerManagementRepository
) : TrackerListRepository {
    override suspend fun loadTrackers(forceRefresh: Boolean): RepositoryResult<List<Tracker>> =
        trackerManagementRepository.loadTrackers(forceRefresh = forceRefresh)
}
