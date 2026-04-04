package com.geovault.tracker.presentation

import com.geovault.tracker.Group

data class TrackerMapGroupModeSelection(
    val groupId: String?,
    val trackerIds: Set<String>
)

object TrackerMapGroupModePolicy {
    fun resolveSelection(
        groups: List<Group>,
        hiddenGroupIds: Set<String>,
        hiddenTrackIds: Set<String>,
        hiddenOwnerTrackerIds: Set<String>,
        preferredGroupId: String?,
        preferredTrackerId: String?,
    ): TrackerMapGroupModeSelection {
        val eligibleGroups = groups
            .asSequence()
            .filter { it.is_accepted != false }
            .filter { it.id !in hiddenGroupIds }
            .map { group ->
                val ids = group.track_ids.orEmpty()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && it !in hiddenTrackIds && it !in hiddenOwnerTrackerIds }
                    .toSet()
                group to ids
            }
            .filter { (_, ids) -> ids.isNotEmpty() }
            .toList()
        if (eligibleGroups.isEmpty()) {
            return TrackerMapGroupModeSelection(groupId = null, trackerIds = emptySet())
        }

        val preferredGroup = preferredGroupId?.trim().orEmpty()
        if (preferredGroup.isNotEmpty()) {
            eligibleGroups.firstOrNull { (group, _) -> group.id == preferredGroup }?.let { resolved ->
                return TrackerMapGroupModeSelection(
                    groupId = resolved.first.id,
                    trackerIds = resolved.second
                )
            }
        }

        val preferredId = preferredTrackerId?.trim().orEmpty()
        val matchingPreferred = if (preferredId.isNotEmpty()) {
            eligibleGroups.firstOrNull { (_, ids) -> preferredId in ids }
        } else {
            null
        }
        val resolved = matchingPreferred ?: eligibleGroups
            .sortedWith(compareBy({ it.first.name.lowercase() }, { it.first.id }))
            .first()
        return TrackerMapGroupModeSelection(
            groupId = resolved.first.id,
            trackerIds = resolved.second
        )
    }
}
