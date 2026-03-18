package com.geovault.tracker.fragments.map

import android.content.Context
import com.geovault.tracker.Group
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerCoordinatesResponse
import com.geovault.tracker.pipeline.TrackPointEvent
import kotlinx.coroutines.flow.Flow

interface MapTrackRepository {
    suspend fun getTrackers(context: Context, forceRefresh: Boolean = false): List<Tracker>
    suspend fun getTracker(context: Context, id: String, forceRefresh: Boolean = false): Tracker?
    suspend fun getTrackerGeometry(context: Context, id: String): Tracker?
    suspend fun getTrackerCoordinates(context: Context, id: String): TrackerCoordinatesResponse?
    suspend fun getTrackersGeometry(context: Context, trackerIds: List<String>, allData: Boolean = true): List<Tracker>
    fun getTrackerFromCache(id: String): Tracker?
}

interface MapGroupRepository {
    suspend fun getGroups(context: Context, forceRefresh: Boolean = false): List<Group>
}

interface MapVisibilityRepository {
    suspend fun getMapVisibility(context: Context): MapVisibilityResponse?
}

interface MapStreamingRepository {
    val events: Flow<TrackPointEvent>
}

