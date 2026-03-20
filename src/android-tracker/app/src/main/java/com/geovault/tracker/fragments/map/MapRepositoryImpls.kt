package com.geovault.tracker.fragments.map

import com.geovault.tracker.Group
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerCoordinatesResponse
import com.geovault.tracker.data.GroupManagementRepository
import com.geovault.tracker.data.TrackerManagementRepository
import com.geovault.tracker.pipeline.TrackPointBusGateway
import com.geovault.tracker.pipeline.TrackPointEvent
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class TrackerRepositoryMapTrackRepository @Inject constructor(
    private val trackerRepository: TrackerManagementRepository
) : MapTrackRepository {
    override suspend fun getTrackers(forceRefresh: Boolean): RepositoryResult<List<Tracker>> =
        trackerRepository.loadTrackers(forceRefresh = forceRefresh)

    override suspend fun getTracker(id: String, forceRefresh: Boolean): RepositoryResult<Tracker> =
        trackerRepository.loadTracker(id)

    override suspend fun getTrackerGeometry(id: String, allData: Boolean): RepositoryResult<Tracker> =
        trackerRepository.loadTrackerGeometry(id, allData = allData)

    override suspend fun getTrackerCoordinates(id: String, allData: Boolean): RepositoryResult<TrackerCoordinatesResponse> {
        return when (val geometryResult = trackerRepository.loadTrackerGeometry(id, allData = allData)) {
            is RepositoryResult.Success -> {
                RepositoryResult.Success(
                    TrackerCoordinatesResponse(
                        coordinates = geometryResult.data.geometry?.coordinates ?: emptyList(),
                        point_params = geometryResult.data.point_params
                    )
                )
            }
            is RepositoryResult.Failure -> RepositoryResult.Failure(geometryResult.error)
        }
    }

    override suspend fun getTrackersCoordinates(
        trackerIds: List<String>,
        allData: Boolean
    ): RepositoryResult<Map<String, TrackerCoordinatesResponse>> {
        val result = linkedMapOf<String, TrackerCoordinatesResponse>()
        for (trackerId in trackerIds) {
            when (val coordsResult = getTrackerCoordinates(trackerId, allData = allData)) {
                is RepositoryResult.Success -> result[trackerId] = coordsResult.data
                is RepositoryResult.Failure -> return RepositoryResult.Failure(coordsResult.error)
            }
        }
        return RepositoryResult.Success(result)
    }

    override fun getTrackerFromCache(id: String): Tracker? = trackerRepository.getTrackerFromCache(id)

    override fun cancelGeometryRequest() {
        // No-op: coroutine repository calls are lifecycle-cancelled by callers.
    }
}

class TrackerRepositoryMapGroupRepository @Inject constructor(
    private val groupRepository: GroupManagementRepository
) : MapGroupRepository {
    override suspend fun getGroups(forceRefresh: Boolean): RepositoryResult<List<Group>> =
        groupRepository.loadGroups(forceRefresh = forceRefresh)
}

class TrackerRepositoryMapVisibilityRepository @Inject constructor(
    private val trackerRepository: TrackerManagementRepository
) : MapVisibilityRepository {
    override suspend fun getMapVisibility(): RepositoryResult<MapVisibilityResponse> =
        trackerRepository.loadMapVisibility(forceRefresh = false)
}

class TrackPointBusStreamingRepository @Inject constructor() : MapStreamingRepository {
    override val events: Flow<TrackPointEvent> = TrackPointBusGateway.events
}
