package com.geovault.tracker.fragments.map

import android.content.Context
import com.geovault.tracker.AppError
import com.geovault.tracker.Group
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerCoordinatesResponse
import com.geovault.tracker.TrackerRepository
import com.geovault.tracker.pipeline.TrackPointBusGateway
import com.geovault.tracker.pipeline.TrackPointEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume

class TrackerRepositoryMapTrackRepository @Inject constructor(
    @param:ApplicationContext private val appContext: Context
) : MapTrackRepository {
    override suspend fun getTrackers(forceRefresh: Boolean): RepositoryResult<List<Tracker>> =
        suspendCancellableCoroutine { continuation ->
            TrackerRepository.getTrackersResult(appContext, forceRefresh = forceRefresh) { result ->
                continuation.resume(result)
            }
        }

    override suspend fun getTracker(id: String, forceRefresh: Boolean): RepositoryResult<Tracker> =
        suspendCancellableCoroutine { continuation ->
            TrackerRepository.getTracker(appContext, id, forceRefresh = forceRefresh) { tracker ->
                val result = if (tracker != null) {
                    RepositoryResult.Success(tracker)
                } else {
                    RepositoryResult.Failure(AppError.NotFound)
                }
                continuation.resume(result)
            }
        }

    override suspend fun getTrackerGeometry(id: String, allData: Boolean): RepositoryResult<Tracker> =
        suspendCancellableCoroutine { continuation ->
            TrackerRepository.getTrackerGeometryResult(appContext, id, allData = allData) { result ->
                continuation.resume(result)
            }
        }

    override suspend fun getTrackerCoordinates(id: String, allData: Boolean): RepositoryResult<TrackerCoordinatesResponse> =
        suspendCancellableCoroutine { continuation ->
            TrackerRepository.getTrackerCoordinates(appContext, id, allData) { response ->
                val result = if (response != null) {
                    RepositoryResult.Success(response)
                } else {
                    RepositoryResult.Failure(AppError.Network)
                }
                continuation.resume(result)
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

    override fun getTrackerFromCache(id: String): Tracker? = TrackerRepository.getTrackerFromCache(id)

    override fun cancelGeometryRequest() {
        TrackerRepository.cancelGeometryRequest()
    }
}

class TrackerRepositoryMapGroupRepository @Inject constructor(
    @param:ApplicationContext private val appContext: Context
) : MapGroupRepository {
    override suspend fun getGroups(forceRefresh: Boolean): RepositoryResult<List<Group>> =
        suspendCancellableCoroutine { continuation ->
            TrackerRepository.getGroups(appContext, forceRefresh = forceRefresh) { groups ->
                val result = if (groups != null) {
                    RepositoryResult.Success(groups)
                } else {
                    RepositoryResult.Failure(AppError.Network)
                }
                continuation.resume(result)
            }
        }
}

class TrackerRepositoryMapVisibilityRepository @Inject constructor(
    @param:ApplicationContext private val appContext: Context
) : MapVisibilityRepository {
    override suspend fun getMapVisibility(): RepositoryResult<MapVisibilityResponse> =
        suspendCancellableCoroutine { continuation ->
            TrackerRepository.getMapVisibility(appContext) { visibility ->
                val result = if (visibility != null) {
                    RepositoryResult.Success(visibility)
                } else {
                    RepositoryResult.Failure(AppError.Network)
                }
                continuation.resume(result)
            }
        }
}

class TrackPointBusStreamingRepository @Inject constructor() : MapStreamingRepository {
    override val events: Flow<TrackPointEvent> = TrackPointBusGateway.events
}
