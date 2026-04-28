package com.geovault.tracker.presentation

import com.geovault.tracker.Group
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.Tracker

enum class HiddenMapItemType {
    TRACKER,
    GROUP,
}

data class HiddenMapItem(
    val id: String,
    val name: String,
    val type: HiddenMapItemType,
)

object HiddenMapItemsPolicy {
    fun hiddenOwnerTrackerIds(trackers: List<Tracker>): Set<String> {
        return trackers
            .asSequence()
            .filter { it.isOwner() && it.settingBoolean("hidden") == true }
            .map { it.id.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    fun visibleTrackerIdsForMap(
        rosterTrackerIds: Collection<String>,
        mapVisibility: MapVisibilityResponse?,
        trackers: List<Tracker>,
    ): Set<String> {
        val hiddenTrackIds = mapVisibility?.hidden_track_ids.orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val hiddenOwnerIds = hiddenOwnerTrackerIds(trackers)
        return rosterTrackerIds
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it !in hiddenTrackIds && it !in hiddenOwnerIds }
            .toSet()
    }

    fun buildHiddenItems(
        mapVisibility: MapVisibilityResponse?,
        trackers: List<Tracker>,
        groups: List<Group>,
    ): List<HiddenMapItem> {
        if (mapVisibility == null) return emptyList()
        val trackerNameById = trackers.associate { it.id to it.name }
        val groupNameById = groups.associate { it.id to it.name }
        val hiddenTrackers = mapVisibility.hidden_track_ids.map { trackerId ->
            HiddenMapItem(
                id = trackerId,
                name = trackerNameById[trackerId].orEmpty().ifBlank { trackerId },
                type = HiddenMapItemType.TRACKER
            )
        }
        val hiddenGroups = mapVisibility.hidden_group_ids.map { groupId ->
            HiddenMapItem(
                id = groupId,
                name = groupNameById[groupId].orEmpty().ifBlank { groupId },
                type = HiddenMapItemType.GROUP
            )
        }
        return (hiddenTrackers + hiddenGroups).sortedWith(
            compareBy<HiddenMapItem>({ it.type.name }, { it.name.lowercase() }, { it.id })
        )
    }
}

private fun Tracker.settingBoolean(key: String): Boolean? {
    val raw = settings?.get(key) ?: return null
    return when (raw) {
        is Boolean -> raw
        is String -> raw.equals("true", ignoreCase = true)
        is Number -> raw.toInt() != 0
        else -> null
    }
}
