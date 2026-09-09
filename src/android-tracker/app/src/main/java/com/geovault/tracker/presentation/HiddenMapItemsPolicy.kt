package com.geovault.tracker.presentation

import com.geovault.tracker.Group
import com.geovault.tracker.MapVisibilityRequest
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
            .filter { it.isOwner() && it.catalogSettings.hidden }
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

    fun buildUnhideItemRequest(
        mapVisibility: MapVisibilityResponse,
        item: HiddenMapItem,
    ): MapVisibilityRequest {
        return when (item.type) {
            HiddenMapItemType.TRACKER -> MapVisibilityRequest(
                hidden_track_ids = mapVisibility.hidden_track_ids.filterNot { it == item.id },
                hidden_group_ids = mapVisibility.hidden_group_ids,
            )
            HiddenMapItemType.GROUP -> MapVisibilityRequest(
                hidden_track_ids = mapVisibility.hidden_track_ids,
                hidden_group_ids = mapVisibility.hidden_group_ids.filterNot { it == item.id },
            )
        }
    }

    fun buildUnhideAllRequest(): MapVisibilityRequest {
        return MapVisibilityRequest(hidden_track_ids = emptyList(), hidden_group_ids = emptyList())
    }
}

