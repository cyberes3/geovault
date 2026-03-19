package com.geovault.tracker.data

import android.content.Context
import com.geovault.tracker.AppError
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume

class TrackerRepositoryTrackerDetailRepository @Inject constructor(
    @param:ApplicationContext private val appContext: Context
) : TrackerDetailRepository {
    override suspend fun loadTrackerMetadata(
        trackerId: String,
        forceRefresh: Boolean
    ): RepositoryResult<Tracker> = suspendCancellableCoroutine { continuation ->
        TrackerRepository.getTracker(appContext, trackerId, forceRefresh = forceRefresh) { tracker ->
            val result = if (tracker != null) {
                RepositoryResult.Success(tracker)
            } else {
                RepositoryResult.Failure(AppError.NotFound)
            }
            continuation.resume(result)
        }
    }

    override suspend fun refreshTrackers() {
        suspendCancellableCoroutine<Unit> { continuation ->
            TrackerRepository.getTrackers(appContext, forceRefresh = true) {
                continuation.resume(Unit)
            }
        }
    }

    override fun clearSelectedTrackerCaches() {
        TrackerRepository.clearSelectedTrackerCaches()
    }
}
