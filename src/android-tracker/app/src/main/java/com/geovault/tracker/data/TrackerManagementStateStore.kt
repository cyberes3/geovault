package com.geovault.tracker.data

import com.geovault.tracker.Group
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.Tracker
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface TrackerManagementEvent {
    data class TrackerUpserted(val tracker: Tracker) : TrackerManagementEvent
    data class TrackerDeleted(val trackerId: String) : TrackerManagementEvent
    data class HistoryCleared(val trackerId: String) : TrackerManagementEvent
    data class TrackersRefreshed(val trackers: List<Tracker>) : TrackerManagementEvent
    data class GroupUpserted(val group: Group) : TrackerManagementEvent
    data class GroupDeleted(val groupId: String) : TrackerManagementEvent
    data class GroupsRefreshed(val groups: List<Group>) : TrackerManagementEvent
    data class MapVisibilityChanged(val value: MapVisibilityResponse) : TrackerManagementEvent
}

@Singleton
class TrackerManagementStateStore @Inject constructor() {
    private val _events = MutableSharedFlow<TrackerManagementEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<TrackerManagementEvent> = _events.asSharedFlow()

    private val _trackers = MutableStateFlow<List<Tracker>>(emptyList())
    val trackers: StateFlow<List<Tracker>> = _trackers.asStateFlow()

    private val _groups = MutableStateFlow<List<Group>>(emptyList())
    val groups: StateFlow<List<Group>> = _groups.asStateFlow()

    private val _mapVisibility = MutableStateFlow<MapVisibilityResponse?>(null)
    val mapVisibility: StateFlow<MapVisibilityResponse?> = _mapVisibility.asStateFlow()

    fun publishTrackers(trackers: List<Tracker>) {
        _trackers.value = trackers
        _events.tryEmit(TrackerManagementEvent.TrackersRefreshed(trackers))
    }

    fun publishTracker(tracker: Tracker) {
        _trackers.value = _trackers.value
            .filterNot { it.id == tracker.id }
            .plus(tracker)
            .sortedBy { it.name.lowercase() }
        _events.tryEmit(TrackerManagementEvent.TrackerUpserted(tracker))
    }

    fun deleteTracker(trackerId: String) {
        _trackers.value = _trackers.value.filterNot { it.id == trackerId }
        _events.tryEmit(TrackerManagementEvent.TrackerDeleted(trackerId))
    }

    fun publishHistoryCleared(trackerId: String) {
        _events.tryEmit(TrackerManagementEvent.HistoryCleared(trackerId))
    }

    fun publishGroups(groups: List<Group>) {
        _groups.value = groups
        _events.tryEmit(TrackerManagementEvent.GroupsRefreshed(groups))
    }

    fun publishGroup(group: Group) {
        _groups.value = _groups.value
            .filterNot { it.id == group.id }
            .plus(group)
            .sortedBy { it.name.lowercase() }
        _events.tryEmit(TrackerManagementEvent.GroupUpserted(group))
    }

    fun deleteGroup(groupId: String) {
        _groups.value = _groups.value.filterNot { it.id == groupId }
        _events.tryEmit(TrackerManagementEvent.GroupDeleted(groupId))
    }

    fun publishMapVisibility(value: MapVisibilityResponse) {
        _mapVisibility.value = value
        _events.tryEmit(TrackerManagementEvent.MapVisibilityChanged(value))
    }
}
