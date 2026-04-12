package com.geovault.tracker.presentation

import com.geovault.tracker.Group
import com.geovault.tracker.Tracker

enum class HiddenTrackerItemType {
    TRACKER,
    GROUP,
}

data class HiddenTrackerItem(
    val id: String,
    val name: String,
    val type: HiddenTrackerItemType,
)

object HiddenTrackersPolicy {
    fun buildItems(trackers: List<Tracker>, groups: List<Group>): List<HiddenTrackerItem> {
        val hiddenTrackers = trackers
            .asSequence()
            .filter { it.isOwner() && ((it.settings?.get("hidden") as? Boolean) == true) }
            .map { HiddenTrackerItem(id = it.id, name = it.name, type = HiddenTrackerItemType.TRACKER) }
            .sortedBy { it.name.lowercase() }
            .toList()
        val hiddenGroups = groups
            .asSequence()
            .filter { it.isOwner() && it.hidden == true }
            .map { HiddenTrackerItem(id = it.id, name = it.name, type = HiddenTrackerItemType.GROUP) }
            .sortedBy { it.name.lowercase() }
            .toList()
        return hiddenTrackers + hiddenGroups
    }
}
