package com.geovault.tracker.data

import com.geovault.tracker.Tracker

class TrackerDataRepositoryImpl(
    private val trackerManagementRepository: TrackerManagementRepository
) : TrackerListRepository {
    override suspend fun loadTrackers(forceRefresh: Boolean): List<Tracker> =
        trackerManagementRepository.loadTrackers(forceRefresh = forceRefresh)
}
