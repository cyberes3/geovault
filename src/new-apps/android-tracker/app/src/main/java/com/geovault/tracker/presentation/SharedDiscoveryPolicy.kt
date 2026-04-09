package com.geovault.tracker.presentation

import com.geovault.tracker.AvailableToAddGroup
import com.geovault.tracker.AvailableToAddItem
import com.geovault.tracker.AvailableToAddResponse
import com.geovault.tracker.Group
import com.geovault.tracker.Tracker

data class SharedDiscoveryBuckets(
    val onMyMapTrackers: List<AvailableToAddItem>,
    val onMyMapGroups: List<AvailableToAddGroup>,
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
            onMyMapTrackers = emptyList(),
            onMyMapGroups = emptyList(),
            incomingTrackers = emptyList(),
            incomingGroups = emptyList(),
            publicTrackers = emptyList(),
            publicGroups = emptyList()
        )
        val onMyMapTrackers = computeVisibleSharedTrackers(trackers, groups)
            .sortedWith(compareBy({ it.subscribed_at ?: Long.MAX_VALUE }, { it.name.lowercase() }))
            .map {
                AvailableToAddItem(
                    id = it.id,
                    name = it.name,
                    color = it.color,
                    owner_email = it.owner_email,
                )
            }
        val onMyMapGroups = computeVisibleSharedGroups(groups).map {
            AvailableToAddGroup(
                id = it.id,
                name = it.name,
                owner_email = it.owner_email,
                track_ids = it.track_ids.orEmpty(),
            )
        }

        val incomingTrackers = available.shared_with_me
            .distinctBy { normalizeSharedId(it.id) }
            .filter { normalizeSharedId(it.id).isNotEmpty() }
            .sortedBy { it.name.lowercase() }

        val incomingGroups = available.shared_with_me_groups
            .distinctBy { normalizeSharedId(it.id) }
            .filter { normalizeSharedId(it.id).isNotEmpty() }
            // Pending shared groups should not expose per-track membership pre-acceptance.
            .map { it.copy(track_ids = emptyList()) }
            .sortedBy { it.name.lowercase() }

        val publicTrackers = available.public
            .distinctBy { normalizeSharedId(it.id) }
            .filter { id ->
                val normalized = normalizeSharedId(id.id)
                normalized.isNotEmpty()
            }
            .sortedBy { it.name.lowercase() }

        val publicGroups = available.public_groups
            .distinctBy { normalizeSharedId(it.id) }
            .filter { group ->
                val normalizedGroupId = normalizeSharedId(group.id)
                normalizedGroupId.isNotEmpty()
            }
            .map { group ->
                group.copy(
                    track_ids = group.track_ids
                        .map { normalizeSharedId(it) }
                        .filter { it.isNotEmpty() }
                        .distinct()
                )
            }
            .sortedBy { it.name.lowercase() }

        return SharedDiscoveryBuckets(
            onMyMapTrackers = onMyMapTrackers,
            onMyMapGroups = onMyMapGroups,
            incomingTrackers = incomingTrackers,
            incomingGroups = incomingGroups,
            publicTrackers = publicTrackers,
            publicGroups = publicGroups
        )
    }
}
