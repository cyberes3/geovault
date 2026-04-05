package com.geovault.tracker.presentation

import com.geovault.tracker.AvailableToAddGroup
import com.geovault.tracker.AvailableToAddItem
import com.geovault.tracker.AvailableToAddResponse
import com.geovault.tracker.Group
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.Tracker

enum class SharedSubTab {
    SHARED,
    DISCOVER,
    PUBLIC,
}

data class SharedUiState(
    val subTab: SharedSubTab = SharedSubTab.SHARED,
    val trackers: List<Tracker> = emptyList(),
    val groups: List<Group> = emptyList(),
    val availableToAdd: AvailableToAddResponse? = null,
    val mapVisibility: MapVisibilityResponse? = null,
    val sharedQuery: String = "",
    val discoverQuery: String = "",
    val publicQuery: String = "",
    val isLoading: Boolean = false,
    val hasCompletedInitialLoad: Boolean = false,
) {
    private val discoveryBuckets: SharedDiscoveryBuckets
        get() = SharedDiscoveryPolicy.derive(
            availableToAdd = availableToAdd,
            trackers = trackers,
            groups = groups
        )

    val visibleSharedTrackers: List<Tracker>
        get() = computeVisibleSharedTrackers(trackers, groups)

    val visibleSharedGroups: List<Group>
        get() = computeVisibleSharedGroups(groups)

    val sharedSurfaceItems: List<SharedSurfaceItem>
        get() = computeSharedSurfaceItems(trackers, groups)

    val incomingTrackers: List<AvailableToAddItem>
        get() = discoveryBuckets.incomingTrackers

    val incomingGroups: List<AvailableToAddGroup>
        get() = discoveryBuckets.incomingGroups

    val publicDiscoverTrackers: List<AvailableToAddItem>
        get() = discoveryBuckets.publicTrackers

    val publicDiscoverGroups: List<AvailableToAddGroup>
        get() = discoveryBuckets.publicGroups

    val filteredSections: SharedFilteredSections
        get() = deriveSharedFilteredSections(
            sharedItems = sharedSurfaceItems,
            incomingTrackers = incomingTrackers,
            incomingGroups = incomingGroups,
            publicTrackers = publicDiscoverTrackers,
            publicGroups = publicDiscoverGroups,
            sharedQuery = sharedQuery,
            discoverQuery = discoverQuery,
            publicQuery = publicQuery,
        )
}
