package com.geovault.tracker.data

import com.geovault.common.concurrent.GeoVaultStateStore
import com.geovault.tracker.AvailableToAddResponse
import com.geovault.tracker.Group
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.Tracker
import kotlinx.coroutines.flow.StateFlow

data class CatalogState(
    val trackers: List<Tracker> = emptyList(),
    val groups: List<Group> = emptyList(),
    val mapVisibility: MapVisibilityResponse? = null,
    val availableToAdd: AvailableToAddResponse? = null,
    val selectedTrackerId: String = "",
    val trackersHydrated: Boolean = false,
    val groupsHydrated: Boolean = false,
    val mapVisibilityHydrated: Boolean = false,
)

class CatalogStateStore(
    private val management: TrackerManagementStateStore,
) {
    private val writeLock = Any()
    private val documents = GeoVaultStateStore(CatalogState())
    val state: StateFlow<CatalogState> = documents.state

    val trackers get() = management.trackers
    val groups get() = management.groups
    val mapVisibility get() = management.mapVisibility
    val events get() = management.events

    fun cachedTrackers(): List<Tracker>? = synchronized(writeLock) {
        state.value.takeIf { it.trackersHydrated }?.trackers
    }

    fun cachedGroups(): List<Group>? = synchronized(writeLock) {
        state.value.takeIf { it.groupsHydrated }?.groups
    }

    fun cachedMapVisibility(): MapVisibilityResponse? = synchronized(writeLock) {
        state.value.takeIf { it.mapVisibilityHydrated }?.mapVisibility
    }

    fun tracker(trackerId: String): Tracker? {
        val id = trackerId.trim()
        if (id.isEmpty()) return null
        return cachedTrackers()?.firstOrNull { it.id == id }
            ?: management.trackers.value.firstOrNull { it.id == id }
    }

    fun canonicalizeTrackers(trackers: List<Tracker>): List<Tracker> =
        management.canonicalizeTrackers(trackers)

    fun replaceTrackers(trackers: List<Tracker>) {
        synchronized(writeLock) {
            management.publishTrackers(trackers)
            documents.update {
                it.copy(trackers = management.trackers.value, trackersHydrated = true)
            }
        }
    }

    fun upsertTracker(tracker: Tracker, emitEvent: Boolean = true) {
        synchronized(writeLock) {
            management.publishTracker(tracker, emitEvent)
            documents.update {
                it.copy(trackers = management.trackers.value, trackersHydrated = true)
            }
        }
    }

    fun removeTracker(trackerId: String) {
        synchronized(writeLock) {
            management.deleteTracker(trackerId)
            documents.update { it.copy(trackers = management.trackers.value) }
        }
    }

    fun publishHistoryCleared(trackerId: String) {
        management.publishHistoryCleared(trackerId)
    }

    fun replaceGroups(groups: List<Group>) {
        synchronized(writeLock) {
            management.publishGroups(groups)
            documents.update {
                it.copy(groups = management.groups.value, groupsHydrated = true)
            }
        }
    }

    fun upsertGroup(group: Group, emitEvent: Boolean = true) {
        synchronized(writeLock) {
            management.publishGroup(group, emitEvent)
            documents.update {
                it.copy(groups = management.groups.value, groupsHydrated = true)
            }
        }
    }

    fun removeGroup(groupId: String) {
        synchronized(writeLock) {
            management.deleteGroup(groupId)
            documents.update { it.copy(groups = management.groups.value) }
        }
    }

    fun replaceMapVisibility(value: MapVisibilityResponse) {
        synchronized(writeLock) {
            management.publishMapVisibility(value)
            documents.update {
                it.copy(mapVisibility = management.mapVisibility.value, mapVisibilityHydrated = true)
            }
        }
    }

    fun setSelection(trackerId: String) {
        documents.update { it.copy(selectedTrackerId = trackerId.trim()) }
    }

    fun setAvailableToAdd(available: AvailableToAddResponse?) {
        documents.update { it.copy(availableToAdd = available) }
    }

    fun clearAll() {
        synchronized(writeLock) {
            management.clearAll()
            documents.replace(CatalogState())
        }
    }
}
