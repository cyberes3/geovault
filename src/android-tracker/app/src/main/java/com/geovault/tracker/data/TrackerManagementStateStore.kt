package com.geovault.tracker.data

import com.geovault.common.sort.NaturalSort
import com.geovault.tracker.Group
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.Tracker
import java.util.Locale
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

class TrackerManagementStateStore {
    private fun trackerNameComparator(): Comparator<Tracker> {
        val locale = Locale.getDefault()
        return NaturalSort.byName(locale) { tracker: Tracker ->
            tracker.name
        }.thenBy { tracker ->
            tracker.id.lowercase(locale)
        }
    }

    fun canonicalizeTrackers(trackers: List<Tracker>): List<Tracker> {
        return trackers.sortedWith(trackerNameComparator())
    }

    private val _events = MutableSharedFlow<TrackerManagementEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<TrackerManagementEvent> = _events.asSharedFlow()

    private val _trackers = MutableStateFlow<List<Tracker>>(emptyList())
    val trackers: StateFlow<List<Tracker>> = _trackers.asStateFlow()

    private val _groups = MutableStateFlow<List<Group>>(emptyList())
    val groups: StateFlow<List<Group>> = _groups.asStateFlow()

    private val _mapVisibility = MutableStateFlow<MapVisibilityResponse?>(null)
    val mapVisibility: StateFlow<MapVisibilityResponse?> = _mapVisibility.asStateFlow()

    fun publishTrackers(trackers: List<Tracker>) {
        val sortedTrackers = canonicalizeTrackers(trackers)
        if (_trackers.value == sortedTrackers) return
        _trackers.value = sortedTrackers
        _events.tryEmit(TrackerManagementEvent.TrackersRefreshed(sortedTrackers))
    }

    fun publishTracker(tracker: Tracker, emitEvent: Boolean = true) {
        val existing = _trackers.value.firstOrNull { it.id == tracker.id }
        if (existing == tracker) return
        _trackers.value = _trackers.value
            .filterNot { it.id == tracker.id }
            .plus(tracker)
            .let(::canonicalizeTrackers)
        if (emitEvent) {
            _events.tryEmit(TrackerManagementEvent.TrackerUpserted(tracker))
        }
    }

    fun deleteTracker(trackerId: String) {
        _trackers.value = _trackers.value.filterNot { it.id == trackerId }
        _events.tryEmit(TrackerManagementEvent.TrackerDeleted(trackerId))
    }

    fun publishHistoryCleared(trackerId: String) {
        _events.tryEmit(TrackerManagementEvent.HistoryCleared(trackerId))
    }

    fun publishGroups(groups: List<Group>) {
        val sortedGroups = groups.sortedWith(NaturalSort.byName(Locale.getDefault()) { it.name })
        if (_groups.value == sortedGroups) return
        _groups.value = sortedGroups
        _events.tryEmit(TrackerManagementEvent.GroupsRefreshed(sortedGroups))
    }

    fun publishGroup(group: Group, emitEvent: Boolean = true) {
        val existing = _groups.value.firstOrNull { it.id == group.id }
        if (existing == group) return
        _groups.value = _groups.value
            .filterNot { it.id == group.id }
            .plus(group)
            .sortedWith(NaturalSort.byName(Locale.getDefault()) { it.name })
        if (emitEvent) {
            _events.tryEmit(TrackerManagementEvent.GroupUpserted(group))
        }
    }

    fun deleteGroup(groupId: String) {
        _groups.value = _groups.value.filterNot { it.id == groupId }
        _events.tryEmit(TrackerManagementEvent.GroupDeleted(groupId))
    }

    fun publishMapVisibility(value: MapVisibilityResponse) {
        if (_mapVisibility.value == value) return
        _mapVisibility.value = value
        _events.tryEmit(TrackerManagementEvent.MapVisibilityChanged(value))
    }

    fun clearAll() {
        _trackers.value = emptyList()
        _groups.value = emptyList()
        _mapVisibility.value = null
    }
}
