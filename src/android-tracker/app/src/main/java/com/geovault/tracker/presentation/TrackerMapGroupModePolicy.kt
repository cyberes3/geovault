package com.geovault.tracker.presentation

import com.geovault.tracker.Group

data class TrackerMapGroupModeOption(
    val groupId: String,
    val groupName: String,
    val trackerIds: Set<String>
)

data class TrackerMapGroupModeSelection(
    val groupId: String?,
    val trackerIds: Set<String>
)

object TrackerMapGroupModePolicy {
    fun resolveEligibleGroups(
        groups: List<Group>,
        hiddenGroupIds: Set<String>,
        hiddenTrackIds: Set<String>,
        hiddenOwnerTrackerIds: Set<String>,
        rosterTrackerIds: Set<String>,
    ): List<TrackerMapGroupModeOption> {
        return eligibleGroups(
            groups = groups,
            hiddenGroupIds = hiddenGroupIds,
            hiddenTrackIds = hiddenTrackIds,
            hiddenOwnerTrackerIds = hiddenOwnerTrackerIds,
            rosterTrackerIds = rosterTrackerIds,
        ).map { (group, ids) ->
            TrackerMapGroupModeOption(
                groupId = group.id,
                groupName = group.name,
                trackerIds = ids
            )
        }
    }

    fun resolveSelection(
        groups: List<Group>,
        hiddenGroupIds: Set<String>,
        hiddenTrackIds: Set<String>,
        hiddenOwnerTrackerIds: Set<String>,
        rosterTrackerIds: Set<String>,
        preferredGroupId: String?,
        preferredTrackerId: String?,
    ): TrackerMapGroupModeSelection {
        val eligibleGroups = eligibleGroups(
            groups = groups,
            hiddenGroupIds = hiddenGroupIds,
            hiddenTrackIds = hiddenTrackIds,
            hiddenOwnerTrackerIds = hiddenOwnerTrackerIds,
            rosterTrackerIds = rosterTrackerIds,
        )
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

    /**
     * ROSTER-MEMBERSHIP FILTER: `group.track_ids` is server data fetched independently of (and
     * refreshed on a different cadence than) the tracker roster, so it can still list a tracker
     * id for a while after that tracker was deleted or unshared and already removed from the
     * roster (`rosterTrackerIds`) elsewhere. Without intersecting against the live roster here,
     * a departed member stays "eligible" group membership until the next groups refresh happens
     * to land -- re-subscribing the websocket to a dead id and leaving a ghost trail/marker on
     * screen for a tracker [com.geovault.tracker.presentation.TrackerMapRosterRemovalPolicy]
     * already tore down.
     */
    private fun eligibleGroups(
        groups: List<Group>,
        hiddenGroupIds: Set<String>,
        hiddenTrackIds: Set<String>,
        hiddenOwnerTrackerIds: Set<String>,
        rosterTrackerIds: Set<String>,
    ): List<Pair<Group, Set<String>>> {
        return groups
            .asSequence()
            .filter { it.is_accepted != false }
            .filter { it.id !in hiddenGroupIds }
            .map { group ->
                val ids = group.track_ids.orEmpty()
                    .map { it.trim() }
                    .filter {
                        it.isNotEmpty() && it !in hiddenTrackIds && it !in hiddenOwnerTrackerIds &&
                            it in rosterTrackerIds
                    }
                    .toSet()
                group to ids
            }
            .filter { (_, ids) -> ids.isNotEmpty() }
            .toList()
    }
}
