package com.geovault.tracker.presentation

import com.geovault.tracker.AvailableToAddGroup
import com.geovault.tracker.AvailableToAddItem
import com.geovault.tracker.AvailableToAddResponse
import com.geovault.tracker.Group
import com.geovault.tracker.Tracker

data class SharedDiscoveryBuckets(
    val incomingTrackers: List<AvailableToAddItem>,
    val incomingGroups: List<AvailableToAddGroup>,
    val publicTrackers: List<AvailableToAddItem>,
    val publicGroups: List<AvailableToAddGroup>,
)

object SharedDiscoveryPolicy {
    fun derive(
        availableToAdd: AvailableToAddResponse?,
        trackers: List<Tracker>,
        groups: List<Group>,
    ): SharedDiscoveryBuckets {
        val available = availableToAdd ?: return SharedDiscoveryBuckets(
            incomingTrackers = emptyList(),
            incomingGroups = emptyList(),
            publicTrackers = emptyList(),
            publicGroups = emptyList()
        )
        val knownTrackerIds = trackers.map { normalizeSharedId(it.id) }.filter { it.isNotEmpty() }.toSet()
        val knownGroupIds = groups.map { normalizeSharedId(it.id) }.filter { it.isNotEmpty() }.toSet()

        val incomingTrackers = available.shared_with_me
            .distinctBy { normalizeSharedId(it.id) }
            .filter { normalizeSharedId(it.id).isNotEmpty() && normalizeSharedId(it.id) !in knownTrackerIds }
            .sortedBy { it.name.lowercase() }
        val incomingTrackerIds = incomingTrackers.map { normalizeSharedId(it.id) }.toSet()

        val incomingGroups = available.shared_with_me_groups
            .distinctBy { normalizeSharedId(it.id) }
            .filter { normalizeSharedId(it.id).isNotEmpty() && normalizeSharedId(it.id) !in knownGroupIds }
            // Legacy parity: pending shared groups should not expose per-track membership pre-acceptance.
            .map { it.copy(track_ids = emptyList()) }
            .sortedBy { it.name.lowercase() }
        val incomingGroupIds = incomingGroups.map { normalizeSharedId(it.id) }.toSet()

        val publicTrackers = available.public
            .distinctBy { normalizeSharedId(it.id) }
            .filter { id ->
                val normalized = normalizeSharedId(id.id)
                normalized.isNotEmpty() &&
                    normalized !in knownTrackerIds &&
                    normalized !in incomingTrackerIds
            }
            .sortedBy { it.name.lowercase() }

        val publicGroups = available.public_groups
            .distinctBy { normalizeSharedId(it.id) }
            .filter { group ->
                val normalizedGroupId = normalizeSharedId(group.id)
                normalizedGroupId.isNotEmpty() &&
                    normalizedGroupId !in knownGroupIds &&
                    normalizedGroupId !in incomingGroupIds
            }
            .map { group ->
                group.copy(
                    track_ids = group.track_ids
                        .map { normalizeSharedId(it) }
                        .filter { it.isNotEmpty() && it !in knownTrackerIds }
                        .distinct()
                )
            }
            .sortedBy { it.name.lowercase() }

        return SharedDiscoveryBuckets(
            incomingTrackers = incomingTrackers,
            incomingGroups = incomingGroups,
            publicTrackers = publicTrackers,
            publicGroups = publicGroups
        )
    }
}
