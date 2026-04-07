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

enum class SharedViewMode {
    SHARED_LIST,
    DISCOVER_OVERLAY,
    PUBLIC_OVERLAY,
}

enum class DiscoverOverlayMode {
    ON_MY_MAP,
    INCOMING,
}

data class SharedUiState(
    val viewMode: SharedViewMode = SharedViewMode.SHARED_LIST,
    val discoverMode: DiscoverOverlayMode = DiscoverOverlayMode.ON_MY_MAP,
    val trackers: List<Tracker> = emptyList(),
    val groups: List<Group> = emptyList(),
    val availableToAdd: AvailableToAddResponse? = null,
    val mapVisibility: MapVisibilityResponse? = null,
    val discoverOnMapQuery: String = "",
    val discoverIncomingQuery: String = "",
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

    val discoverOnMyMapTrackers: List<AvailableToAddItem>
        get() = discoveryBuckets.onMyMapTrackers

    val discoverOnMyMapGroups: List<AvailableToAddGroup>
        get() = discoveryBuckets.onMyMapGroups

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
            discoverOnMyMapTrackers = discoverOnMyMapTrackers,
            discoverOnMyMapGroups = discoverOnMyMapGroups,
            incomingTrackers = incomingTrackers,
            incomingGroups = incomingGroups,
            publicTrackers = publicDiscoverTrackers,
            publicGroups = publicDiscoverGroups,
            discoverOnMapQuery = discoverOnMapQuery,
            discoverIncomingQuery = discoverIncomingQuery,
            publicQuery = publicQuery,
        )
}
