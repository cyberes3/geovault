package com.geovault.tracker.presentation

import com.geovault.tracker.AvailableToAddItem

fun countOverlappingIncomingShares(
    incomingTrackers: List<AvailableToAddItem>,
    acceptedGroupTrackIds: List<String>?,
): Int {
    val incomingIds = incomingTrackers
        .map { normalizeSharedId(it.id) }
        .filter { it.isNotEmpty() }
        .toSet()
    return acceptedGroupTrackIds.orEmpty()
        .count { trackId -> incomingIds.contains(normalizeSharedId(trackId)) }
}
