package com.geovault.tracker.presentation

import com.geovault.common.NaturalSort
import com.geovault.tracker.AvailableToAddGroup
import com.geovault.tracker.AvailableToAddItem
import com.geovault.tracker.Group
import com.geovault.tracker.Tracker
import java.util.Locale

/**
 * Shared parity contract:
 * 1) Shared surface contains only accepted non-owned shared groups and standalone non-owned
 *    shared/public trackers.
 * 2) Trackers represented by a shared group are removed from standalone rows (de-dupe).
 * 3) Shared surface ordering is one naturally sorted list across groups + trackers.
 * 4) Discover/Public sections exclude entities already present on-map and de-dupe between buckets.
 * 5) Search filtering is pure and centralized here so Compose rendering stays declarative.
 */
internal fun normalizeSharedId(id: String?): String = id.orEmpty()

fun isSharedOrPublicNonOwnedTracker(track: Tracker): Boolean {
    if (track.isOwner()) return false
    val v = track.visibility.orEmpty()
    return v == "shared" || v == "public"
}

/**
 * Non-owned shared/public trackers on the user's map, excluding tracks that appear as members of any
 * non-owned group (avoids duplicate rows when the same track is represented via a group).
 */
fun computeVisibleSharedTrackers(
    trackers: List<Tracker>,
    groups: List<Group>,
): List<Tracker> {
    val sharedGroups = computeVisibleSharedGroups(groups)
    val trackIdsInSharedGroups = sharedGroups
        .flatMap { it.track_ids.orEmpty() }
        .map { normalizeSharedId(it) }
        .filter { it.isNotEmpty() }
        .toSet()
    return trackers
        .filter { track ->
            isSharedOrPublicNonOwnedTracker(track) &&
                !trackIdsInSharedGroups.contains(normalizeSharedId(track.id))
        }
        .sortedWith(NaturalSort.naturalOrderBy { it.name.lowercase(Locale.getDefault()) })
}

/**
 * Accepted shared groups the user does not own (public member groups are listed via their tracks, not here).
 */
fun computeVisibleSharedGroups(groups: List<Group>): List<Group> {
    return groups
        .filter { group ->
            !group.isOwner() &&
                (group.visibility ?: "") == "shared" &&
                group.is_accepted == true
        }
        .sortedWith(NaturalSort.naturalOrderBy { it.name.lowercase(Locale.getDefault()) })
}

sealed interface SharedSurfaceItem {
    val sortName: String

    data class TrackerItem(val tracker: Tracker) : SharedSurfaceItem {
        override val sortName: String = tracker.name
    }

    data class GroupItem(val group: Group) : SharedSurfaceItem {
        override val sortName: String = group.name
    }
}

/**
 * Shared surface list: accepted shared groups + non-owned shared/public standalone trackers,
 * sorted together naturally by display name.
 */
fun computeSharedSurfaceItems(
    trackers: List<Tracker>,
    groups: List<Group>,
): List<SharedSurfaceItem> {
    val sharedGroups = computeVisibleSharedGroups(groups).map { SharedSurfaceItem.GroupItem(it) }
    val sharedTrackers = computeVisibleSharedTrackers(trackers, groups)
        .map { SharedSurfaceItem.TrackerItem(it) }
    return (sharedGroups + sharedTrackers).sortedWith(
        NaturalSort.naturalOrderBy { it.sortName.lowercase(Locale.getDefault()) }
    )
}

data class SharedFilteredSections(
    val sharedItems: List<SharedSurfaceItem>,
    val discoverOnMyMapTrackers: List<AvailableToAddItem>,
    val discoverOnMyMapGroups: List<AvailableToAddGroup>,
    val incomingTrackers: List<AvailableToAddItem>,
    val incomingGroups: List<AvailableToAddGroup>,
    val publicTrackers: List<AvailableToAddItem>,
    val publicGroups: List<AvailableToAddGroup>,
)

fun deriveSharedFilteredSections(
    sharedItems: List<SharedSurfaceItem>,
    discoverOnMyMapTrackers: List<AvailableToAddItem>,
    discoverOnMyMapGroups: List<AvailableToAddGroup>,
    incomingTrackers: List<AvailableToAddItem>,
    incomingGroups: List<AvailableToAddGroup>,
    publicTrackers: List<AvailableToAddItem>,
    publicGroups: List<AvailableToAddGroup>,
    discoverOnMapQuery: String,
    discoverIncomingQuery: String,
    publicQuery: String,
    optimisticTrackerAdds: Map<String, Tracker> = emptyMap(),
    optimisticTrackerRemovals: Set<String> = emptySet(),
    optimisticDiscoverOnMapRemovals: Set<String> = emptySet(),
    retainedIncomingTrackers: List<AvailableToAddItem> = emptyList(),
    retainedIncomingGroups: List<AvailableToAddGroup> = emptyList(),
    retainedPublicTrackers: List<AvailableToAddItem> = emptyList(),
    retainedPublicGroups: List<AvailableToAddGroup> = emptyList(),
): SharedFilteredSections {
    val sharedItemsWithOptimistic = applyOptimisticSharedItems(
        sharedItems = sharedItems,
        optimisticTrackerAdds = optimisticTrackerAdds,
        optimisticTrackerRemovals = optimisticTrackerRemovals,
    )
    val filteredSharedItems = sharedItemsWithOptimistic
    val mergedIncomingTrackers = mergeRetainedTrackerItems(incomingTrackers, retainedIncomingTrackers)
    val mergedIncomingGroups = mergeRetainedGroupItems(incomingGroups, retainedIncomingGroups)
    val mergedPublicTrackers = mergeRetainedTrackerItems(publicTrackers, retainedPublicTrackers)
    val mergedPublicGroups = mergeRetainedGroupItems(publicGroups, retainedPublicGroups)
    val filteredOnMyMapTrackers = discoverOnMyMapTrackers.filter { item ->
        matchesSharedSearch(discoverOnMapQuery, item.name, item.owner_email)
    }
    val filteredOnMyMapGroups = discoverOnMyMapGroups.filter { group ->
        matchesSharedSearch(discoverOnMapQuery, group.name, group.owner_email, group.track_ids.size.toString())
    }
    val filteredIncomingTrackers = mergedIncomingTrackers.filter { item ->
        matchesSharedSearch(discoverIncomingQuery, item.name, item.owner_email)
    }
    val filteredIncomingGroups = mergedIncomingGroups.filter { group ->
        matchesSharedSearch(discoverIncomingQuery, group.name, group.owner_email, group.track_ids.size.toString())
    }
    val filteredPublicTrackers = mergedPublicTrackers.filter { item ->
        matchesSharedSearch(publicQuery, item.name, item.owner_email)
    }
    val filteredPublicGroups = mergedPublicGroups.filter { group ->
        matchesSharedSearch(publicQuery, group.name, group.owner_email, group.track_ids.size.toString())
    }
    return SharedFilteredSections(
        sharedItems = filteredSharedItems,
        discoverOnMyMapTrackers = filteredOnMyMapTrackers,
        discoverOnMyMapGroups = filteredOnMyMapGroups,
        incomingTrackers = filteredIncomingTrackers,
        incomingGroups = filteredIncomingGroups,
        publicTrackers = filteredPublicTrackers,
        publicGroups = filteredPublicGroups,
    )
}

private fun applyOptimisticSharedItems(
    sharedItems: List<SharedSurfaceItem>,
    optimisticTrackerAdds: Map<String, Tracker>,
    optimisticTrackerRemovals: Set<String>,
): List<SharedSurfaceItem> {
    val base = sharedItems.filterNot { item ->
        item is SharedSurfaceItem.TrackerItem && optimisticTrackerRemovals.contains(item.tracker.id)
    }
    val existingTrackerIds = base.mapNotNull { (it as? SharedSurfaceItem.TrackerItem)?.tracker?.id }.toSet()
    val optimisticAddItems = optimisticTrackerAdds.values
        .asSequence()
        .filterNot { tracker ->
            optimisticTrackerRemovals.contains(tracker.id) || existingTrackerIds.contains(tracker.id)
        }
        .map { tracker -> SharedSurfaceItem.TrackerItem(tracker) }
        .toList()
    return (base + optimisticAddItems).sortedWith(
        NaturalSort.naturalOrderBy { it.sortName.lowercase(Locale.getDefault()) }
    )
}

private fun mergeRetainedTrackerItems(
    base: List<AvailableToAddItem>,
    retained: List<AvailableToAddItem>,
): List<AvailableToAddItem> {
    val byId = LinkedHashMap<String, AvailableToAddItem>()
    base.forEach { item -> byId[normalizeSharedId(item.id)] = item }
    retained.forEach { item ->
        val id = normalizeSharedId(item.id)
        if (!byId.containsKey(id)) byId[id] = item
    }
    return byId.values
        .sortedWith(
            compareBy(
                { it.name.lowercase(Locale.getDefault()) },
                { normalizeSharedId(it.id) }
            )
        )
}

private fun mergeRetainedGroupItems(
    base: List<AvailableToAddGroup>,
    retained: List<AvailableToAddGroup>,
): List<AvailableToAddGroup> {
    val byId = LinkedHashMap<String, AvailableToAddGroup>()
    base.forEach { group -> byId[normalizeSharedId(group.id)] = group }
    retained.forEach { group ->
        val id = normalizeSharedId(group.id)
        if (!byId.containsKey(id)) byId[id] = group
    }
    return byId.values
        .sortedWith(
            compareBy(
                { it.name.lowercase(Locale.getDefault()) },
                { normalizeSharedId(it.id) }
            )
        )
}


fun matchesSharedSearch(query: String, vararg parts: String?): Boolean {
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isEmpty()) return true
    return parts.any { part ->
        part.orEmpty().lowercase().contains(normalizedQuery)
    }
}
