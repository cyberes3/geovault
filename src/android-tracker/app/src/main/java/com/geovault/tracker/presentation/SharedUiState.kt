package com.geovault.tracker.presentation

import com.geovault.tracker.AvailableToAddGroup
import com.geovault.tracker.AvailableToAddItem
import com.geovault.tracker.AvailableToAddResponse
import com.geovault.tracker.Group
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.Tracker
import com.geovault.tracker.data.PendingTransaction

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
    val sharedListQuery: String = "",
    val discoverOnMapQuery: String = "",
    val discoverIncomingQuery: String = "",
    val publicQuery: String = "",
    val isLoading: Boolean = false,
    val hasCompletedInitialLoad: Boolean = false,
    val mutations: List<PendingTransaction> = emptyList(),
    val selectedTrackerId: String = "",
) {
    val pendingTrackerAdds: Map<String, Tracker>
        get() = mutations.mapNotNull { tx ->
            val id = tx.addedTrackerId ?: return@mapNotNull null
            val tracker = tx.addedTracker ?: return@mapNotNull null
            id to tracker
        }.toMap()

    val pendingTrackerRemovals: Set<String>
        get() = mutations.mapNotNull { it.removalTrackerId }.toSet()

    val queuedIncomingTrackers: Map<String, AvailableToAddItem>
        get() = mutations.mapNotNull { tx ->
            tx.incomingTracker?.let { it.id to it }
        }.toMap()

    val queuedIncomingGroups: Map<String, AvailableToAddGroup>
        get() = mutations.mapNotNull { tx ->
            tx.incomingGroup?.let { it.id to it }
        }.toMap()

    val queuedPublicTrackers: Map<String, AvailableToAddItem>
        get() = mutations.mapNotNull { tx ->
            tx.publicTracker?.let { it.id to it }
        }.toMap()

    val queuedPublicGroups: Map<String, AvailableToAddGroup>
        get() = mutations.mapNotNull { tx ->
            tx.publicGroup?.let { it.id to it }
        }.toMap()

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
            sharedListQuery = sharedListQuery,
            pendingTrackerAdds = pendingTrackerAdds,
            pendingTrackerRemovals = pendingTrackerRemovals,
            queuedIncomingTrackers = queuedIncomingTrackers.values.toList(),
            queuedIncomingGroups = queuedIncomingGroups.values.toList(),
            queuedPublicTrackers = queuedPublicTrackers.values.toList(),
            queuedPublicGroups = queuedPublicGroups.values.toList(),
        )

    val sharedListRows: List<SharedListRowModel>
        get() = filteredSections.sharedItems.toSharedListRows(selectedTrackerId = selectedTrackerId)

    val pendingAddActionKeys: Set<String>
        get() = mutations.filter { it.phase == SharedMutationPhase.PENDING_ADD.name }.map { it.occupancyKey }.toSet()

    val pendingRemoveActionKeys: Set<String>
        get() = mutations.filter { it.phase == SharedMutationPhase.PENDING_REMOVE.name }.map { it.occupancyKey }.toSet()

    val hasInlineMutation: Boolean
        get() = mutations.isNotEmpty()

    val effectiveSubscribedTrackerIds: Set<String>
        get() = trackers.map { it.id }.toSet()
            .plus(pendingTrackerAdds.keys)
            .minus(pendingTrackerRemovals)

    fun isIncomingTrackerAdded(trackerId: String): Boolean =
        queuedIncomingTrackers.containsKey(trackerId)

    fun isIncomingGroupAdded(groupId: String): Boolean =
        queuedIncomingGroups.containsKey(groupId)

    fun isPublicTrackerAdded(trackerId: String): Boolean =
        queuedPublicTrackers.containsKey(trackerId)

    fun isPublicGroupAdded(groupId: String): Boolean =
        queuedPublicGroups.containsKey(groupId)
}
