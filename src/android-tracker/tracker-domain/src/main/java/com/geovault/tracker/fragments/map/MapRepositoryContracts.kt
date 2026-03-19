package com.geovault.tracker.fragments.map

import com.geovault.tracker.Group
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerCoordinatesResponse
import com.geovault.tracker.pipeline.TrackPointEvent
import kotlinx.coroutines.flow.Flow

interface MapTrackRepository {
    suspend fun getTrackers(forceRefresh: Boolean = false): RepositoryResult<List<Tracker>>
    suspend fun getTracker(id: String, forceRefresh: Boolean = false): RepositoryResult<Tracker>
    suspend fun getTrackerGeometry(id: String, allData: Boolean = false): RepositoryResult<Tracker>
    suspend fun getTrackerCoordinates(id: String, allData: Boolean = false): RepositoryResult<TrackerCoordinatesResponse>
    suspend fun getTrackersGeometry(trackerIds: List<String>, allData: Boolean = true): RepositoryResult<List<Tracker>>
    fun getTrackerFromCache(id: String): Tracker?
}

interface MapGroupRepository {
    suspend fun getGroups(forceRefresh: Boolean = false): RepositoryResult<List<Group>>
}

interface MapVisibilityRepository {
    suspend fun getMapVisibility(): RepositoryResult<MapVisibilityResponse>
}

interface MapStreamingRepository {
    val events: Flow<TrackPointEvent>
}
