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
