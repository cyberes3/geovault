package com.geovault.tracker.presentation

import com.geovault.tracker.Group
import com.geovault.tracker.Tracker

fun filterVisibleOwnerTrackersForSearch(
    trackers: List<Tracker>,
    query: String,
): List<Tracker> {
    return trackers
        .filter { tracker ->
            tracker.isOwner() &&
                (tracker.settings?.get("hidden") as? Boolean) != true
        }
        .filter { tracker ->
            matchesTrackerSearch(query, tracker.name, tracker.owner_email)
        }
}

fun filterVisibleOwnerGroupsForSearch(
    groups: List<Group>,
    query: String,
): List<Group> {
    return groups
        .filter { group ->
            group.isOwner() && group.hidden != true
        }
        .filter { group ->
            matchesTrackerSearch(query, group.name, group.owner_email)
        }
}

fun matchesTrackerSearch(query: String, vararg parts: String?): Boolean {
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isEmpty()) return true
    return parts.any { part ->
        part.orEmpty().lowercase().contains(normalizedQuery)
    }
}
