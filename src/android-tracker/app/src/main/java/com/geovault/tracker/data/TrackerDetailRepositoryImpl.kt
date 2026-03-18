package com.geovault.tracker.data

import android.content.Context
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerRepository
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume

class TrackerRepositoryTrackerDetailRepository @Inject constructor() : TrackerDetailRepository {
    override suspend fun loadTrackerGeometry(
        context: Context,
        trackerId: String
    ): RepositoryResult<Tracker> = suspendCancellableCoroutine { continuation ->
        TrackerRepository.getTrackerGeometryResult(context, trackerId) { result ->
            continuation.resume(result)
        }
    }

    override suspend fun refreshTrackers(context: Context) {
        suspendCancellableCoroutine<Unit> { continuation ->
            TrackerRepository.getTrackers(context, forceRefresh = true) {
                continuation.resume(Unit)
            }
        }
    }
}
