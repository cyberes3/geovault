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

enum class SharedMutationPhase {
    PENDING_ADD,
    PENDING_REMOVE,
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
    val pendingOps: Map<String, SharedMutationPhase> = emptyMap(),
    val optimisticTrackerAdds: Map<String, Tracker> = emptyMap(),
    val optimisticTrackerRemovals: Set<String> = emptySet(),
    val optimisticDiscoverOnMapRemovals: Set<String> = emptySet(),
    val retainedIncomingTrackers: Map<String, AvailableToAddItem> = emptyMap(),
    val retainedIncomingGroups: Map<String, AvailableToAddGroup> = emptyMap(),
    val retainedPublicTrackers: Map<String, AvailableToAddItem> = emptyMap(),
    val retainedPublicGroups: Map<String, AvailableToAddGroup> = emptyMap(),
    val selectedTrackerId: String = "",
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
            optimisticTrackerAdds = optimisticTrackerAdds,
            optimisticTrackerRemovals = optimisticTrackerRemovals,
            optimisticDiscoverOnMapRemovals = optimisticDiscoverOnMapRemovals,
            retainedIncomingTrackers = retainedIncomingTrackers.values.toList(),
            retainedIncomingGroups = retainedIncomingGroups.values.toList(),
            retainedPublicTrackers = retainedPublicTrackers.values.toList(),
            retainedPublicGroups = retainedPublicGroups.values.toList(),
        )

    val sharedListRows: List<SharedListRowModel>
        get() = filteredSections.sharedItems.toSharedListRows(selectedTrackerId = selectedTrackerId)

    val pendingAddActionKeys: Set<String>
        get() = pendingOps.filterValues { it == SharedMutationPhase.PENDING_ADD }.keys

    val pendingRemoveActionKeys: Set<String>
        get() = pendingOps.filterValues { it == SharedMutationPhase.PENDING_REMOVE }.keys

    val hasInlineMutation: Boolean
        get() = pendingOps.isNotEmpty()

    val effectiveSubscribedTrackerIds: Set<String>
        get() = trackers.map { it.id }.toSet()
            .plus(optimisticTrackerAdds.keys)
            .minus(optimisticTrackerRemovals)

    fun isIncomingTrackerAdded(trackerId: String): Boolean =
        retainedIncomingTrackers.containsKey(trackerId)

    fun isIncomingGroupAdded(groupId: String): Boolean =
        retainedIncomingGroups.containsKey(groupId)

    fun isPublicTrackerAdded(trackerId: String): Boolean =
        retainedPublicTrackers.containsKey(trackerId)

    fun isPublicGroupAdded(groupId: String): Boolean =
        retainedPublicGroups.containsKey(groupId)
}
