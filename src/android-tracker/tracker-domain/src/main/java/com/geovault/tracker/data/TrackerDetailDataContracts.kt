package com.geovault.tracker.data

import android.content.Context
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker

interface TrackerDetailRepository {
    suspend fun loadTrackerMetadata(
        context: Context,
        trackerId: String,
        forceRefresh: Boolean = false
    ): RepositoryResult<Tracker>
    suspend fun refreshTrackers(context: Context)
}
