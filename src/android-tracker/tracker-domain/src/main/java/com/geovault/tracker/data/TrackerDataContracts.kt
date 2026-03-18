package com.geovault.tracker.data

import android.content.Context
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker

interface TrackerListRepository {
    suspend fun loadTrackers(context: Context, forceRefresh: Boolean): RepositoryResult<List<Tracker>>
}
