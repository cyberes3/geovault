package com.geovault.tracker.data

import com.geovault.tracker.Group
import com.geovault.tracker.AvailableToAddResponse
import com.geovault.tracker.MapVisibilityRequest
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.TrackerAddToGroupCandidate
import com.geovault.tracker.TrackerCheckRequest
import com.geovault.tracker.TrackerCreateRequest
import com.geovault.tracker.TrackerSettingsRequest
import com.geovault.tracker.Tracker
import com.geovault.tracker.UsersResponse

interface TrackerListRepository {
    suspend fun loadTrackers(forceRefresh: Boolean): RepositoryResult<List<Tracker>>
}

interface TrackerManagementRepository {
    suspend fun loadTrackers(forceRefresh: Boolean = false): RepositoryResult<List<Tracker>>
    suspend fun loadAvailableToAdd(forceRefresh: Boolean = false): RepositoryResult<AvailableToAddResponse>
    suspend fun loadTracker(trackerId: String): RepositoryResult<Tracker>
    suspend fun loadTrackerGeometry(trackerId: String): RepositoryResult<Tracker>
    suspend fun createTracker(request: TrackerCreateRequest): RepositoryResult<Tracker>
    suspend fun updateTrackerSettings(
        trackerId: String,
        request: TrackerSettingsRequest,
        publishToStore: Boolean = true
    ): RepositoryResult<Tracker>
    suspend fun deleteTracker(trackerId: String): RepositoryResult<Unit>
    suspend fun clearTrackerHistory(trackerId: String): RepositoryResult<Unit>
    suspend fun leaveShareWithMe(trackerId: String): RepositoryResult<Unit>
    suspend fun unsubscribeTracker(trackerId: String): RepositoryResult<Unit>
    suspend fun subscribeTracker(trackerId: String): RepositoryResult<Tracker>
    suspend fun checkTracker(request: TrackerCheckRequest): RepositoryResult<Boolean>
    fun getTrackerFromCache(trackerId: String): Tracker?
    fun clearSelectedTrackerCaches()
    suspend fun fetchTrackerKml(trackerId: String): RepositoryResult<ByteArray>
    suspend fun loadUsers(): RepositoryResult<UsersResponse>
    suspend fun loadMapVisibility(forceRefresh: Boolean = false): RepositoryResult<MapVisibilityResponse>
    suspend fun patchMapVisibility(request: MapVisibilityRequest): RepositoryResult<MapVisibilityResponse>
}

interface GroupManagementRepository {
    suspend fun loadGroups(forceRefresh: Boolean = false): RepositoryResult<List<Group>>
    suspend fun loadGroup(groupId: String): RepositoryResult<Group>
    suspend fun createGroup(name: String): RepositoryResult<Group>
    suspend fun patchGroup(
        groupId: String,
        request: com.geovault.tracker.GroupPatchRequest,
        publishToStore: Boolean = true
    ): RepositoryResult<Group>
    suspend fun deleteGroup(groupId: String): RepositoryResult<Unit>
    suspend fun addGroupTrack(groupId: String, trackId: String): RepositoryResult<Group>
    suspend fun removeGroupTrack(groupId: String, trackId: String): RepositoryResult<Group>
    suspend fun leaveGroup(groupId: String): RepositoryResult<Unit>
    suspend fun acceptGroupShare(groupId: String): RepositoryResult<Group>
}

interface GroupTrackerEligibilityUseCase {
    fun addableTrackers(
        trackers: List<Tracker>,
        group: Group
    ): List<TrackerAddToGroupCandidate>
}
