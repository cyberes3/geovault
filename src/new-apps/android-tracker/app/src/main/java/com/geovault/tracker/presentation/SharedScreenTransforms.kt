package com.geovault.tracker.presentation

import com.geovault.tracker.Group
import com.geovault.tracker.Tracker

/**
 * Mirrors `sharingSelectors.js` (`computeVisibleSharedTrackers` / `computeVisibleSharedGroups`).
 * TODO: Confirm behavior when `visibility` is null on non-owned trackers (treated as not shared/public).
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
    val sharedGroups = groups.filter { !it.isOwner() }
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
        .sortedBy { it.name.lowercase() }
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
        .sortedBy { it.name.lowercase() }
}
