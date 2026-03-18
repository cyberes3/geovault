package com.geovault.tracker.data

import android.content.Context
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker

interface TrackerDetailRepository {
    suspend fun loadTrackerGeometry(context: Context, trackerId: String): RepositoryResult<Tracker>
    suspend fun refreshTrackers(context: Context)
}
