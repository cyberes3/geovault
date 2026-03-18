package com.geovault.tracker.fragments.map

import android.content.Context
import com.geovault.tracker.Group
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerCoordinatesResponse
import com.geovault.tracker.TrackerRepository
import com.geovault.tracker.pipeline.TrackPointBusGateway
import com.geovault.tracker.pipeline.TrackPointEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume

class TrackerRepositoryMapTrackRepository @Inject constructor() : MapTrackRepository {
    override suspend fun getTrackers(context: Context, forceRefresh: Boolean): List<Tracker> =
        suspendCancellableCoroutine { continuation ->
            TrackerRepository.getTrackers(context, forceRefresh = forceRefresh) { list ->
                continuation.resume(list ?: emptyList())
            }
        }

    override suspend fun getTracker(context: Context, id: String, forceRefresh: Boolean): Tracker? =
        suspendCancellableCoroutine { continuation ->
            TrackerRepository.getTracker(context, id, forceRefresh = forceRefresh) { tracker ->
                continuation.resume(tracker)
            }
        }

    override suspend fun getTrackerGeometry(context: Context, id: String, allData: Boolean): Tracker? =
        suspendCancellableCoroutine { continuation ->
            TrackerRepository.getTrackerGeometry(context, id, allData) { tracker ->
                continuation.resume(tracker)
            }
        }

    override suspend fun getTrackerCoordinates(context: Context, id: String, allData: Boolean): TrackerCoordinatesResponse? =
        suspendCancellableCoroutine { continuation ->
            TrackerRepository.getTrackerCoordinates(context, id, allData) { response ->
                continuation.resume(response)
            }
        }

    override suspend fun getTrackersGeometry(context: Context, trackerIds: List<String>, allData: Boolean): List<Tracker> =
        suspendCancellableCoroutine { continuation ->
            TrackerRepository.getTrackersGeometry(context, trackerIds, allData = allData) { trackers ->
                continuation.resume(trackers ?: emptyList())
            }
        }

    override fun getTrackerFromCache(id: String): Tracker? = TrackerRepository.getTrackerFromCache(id)
}

class TrackerRepositoryMapGroupRepository @Inject constructor() : MapGroupRepository {
    override suspend fun getGroups(context: Context, forceRefresh: Boolean): List<Group> =
        suspendCancellableCoroutine { continuation ->
            TrackerRepository.getGroups(context, forceRefresh = forceRefresh) { groups ->
                continuation.resume(groups ?: emptyList())
            }
        }
}

class TrackerRepositoryMapVisibilityRepository @Inject constructor() : MapVisibilityRepository {
    override suspend fun getMapVisibility(context: Context): MapVisibilityResponse? =
        suspendCancellableCoroutine { continuation ->
            TrackerRepository.getMapVisibility(context) { visibility ->
                continuation.resume(visibility)
            }
        }
}

class TrackPointBusStreamingRepository @Inject constructor() : MapStreamingRepository {
    override val events: Flow<TrackPointEvent> = TrackPointBusGateway.events
}
