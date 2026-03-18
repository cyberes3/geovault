package com.geovault.tracker.data

import android.content.Context
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerRepository
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class TrackerRepositoryTrackerListRepository : TrackerListRepository {
    override suspend fun loadTrackers(context: Context, forceRefresh: Boolean): RepositoryResult<List<Tracker>> =
        suspendCancellableCoroutine { continuation ->
            TrackerRepository.getTrackersResult(context, forceRefresh = forceRefresh) { result ->
                continuation.resume(result)
            }
        }
}
