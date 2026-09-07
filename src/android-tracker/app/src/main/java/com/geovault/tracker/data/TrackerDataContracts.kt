package com.geovault.tracker.data

import com.geovault.tracker.AvailableToAddResponse
import com.geovault.tracker.Group
import com.geovault.tracker.MapVisibilityRequest
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerCheckRequest
import com.geovault.tracker.TrackerCoordinatesResponse
import com.geovault.tracker.TrackerCreateRequest
import com.geovault.tracker.TrackerSettingsRequest
import com.geovault.tracker.UsersResponse

interface TrackerListRepository {
    suspend fun loadTrackers(forceRefresh: Boolean): List<Tracker>
}

interface TrackerManagementRepository {
    suspend fun loadTrackers(forceRefresh: Boolean = false): List<Tracker>
    suspend fun loadAvailableToAdd(forceRefresh: Boolean = false): AvailableToAddResponse
    suspend fun loadTracker(trackerId: String): Tracker
    suspend fun loadTrackerGeometry(trackerId: String): Tracker
    suspend fun loadTrackerCoordinates(trackerId: String): TrackerCoordinatesResponse
    suspend fun loadTrackersGeometry(trackerIds: List<String>): List<Tracker>
    suspend fun createTracker(request: TrackerCreateRequest): Tracker
    suspend fun updateTrackerSettings(
        trackerId: String,
        request: TrackerSettingsRequest,
        publishToStore: Boolean = true
    ): Tracker
    suspend fun deleteTracker(trackerId: String)
    suspend fun clearTrackerHistory(trackerId: String)
    suspend fun leaveShareWithMe(trackerId: String)
    suspend fun unsubscribeTracker(trackerId: String)
    suspend fun subscribeTracker(trackerId: String): Tracker
    suspend fun checkTracker(request: TrackerCheckRequest): Boolean
    fun getTrackerFromCache(trackerId: String): Tracker?
    fun clearSelectedTrackerCaches()
    suspend fun fetchTrackerKml(trackerId: String): ByteArray
    suspend fun loadUsers(): UsersResponse
    suspend fun loadMapVisibility(forceRefresh: Boolean = false): MapVisibilityResponse
    suspend fun patchMapVisibility(request: MapVisibilityRequest): MapVisibilityResponse
    suspend fun clearHiddenItems(targetTypes: List<String>? = null)
}

interface GroupManagementRepository {
    suspend fun loadGroups(forceRefresh: Boolean = false): List<Group>
    suspend fun loadGroup(groupId: String): Group
    suspend fun createGroup(name: String): Group
    suspend fun patchGroup(
        groupId: String,
        request: com.geovault.tracker.GroupPatchRequest,
        publishToStore: Boolean = true
    ): Group
    suspend fun deleteGroup(groupId: String)
    suspend fun addGroupTrack(groupId: String, trackId: String): Group
    suspend fun removeGroupTrack(groupId: String, trackId: String): Group
    suspend fun leaveGroup(groupId: String)
    suspend fun acceptGroupShare(groupId: String): Group
}
