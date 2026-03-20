package com.geovault.tracker.fragments

import com.geovault.tracker.Group
import com.geovault.tracker.Tracker
import javax.inject.Inject

data class SharedSurfaceFilterResult(
    val sharedGroups: List<Group>,
    val sharedTrackers: List<Tracker>,
    val hiddenTrackIds: Set<String>,
    val hiddenGroupIds: Set<String>
)

class SharedSurfaceFilterUseCase @Inject constructor() {
    fun filter(
        groups: List<Group>,
        trackers: List<Tracker>,
        hiddenTrackIds: Set<String>,
        hiddenGroupIds: Set<String>
    ): SharedSurfaceFilterResult {
        val sharedGroups = groups
            .filter { it.is_owner != true && it.visibility == "shared" && it.id !in hiddenGroupIds }
            .filter { it.is_accepted == true }
        val trackIdsInSharedGroups = sharedGroups
            .flatMap { it.track_ids ?: emptyList() }
            .toSet()
        val sharedTrackers = trackers
            .filter { !it.isOwner() && (it.visibility == "shared" || it.visibility == "public") }
            .filter { it.id !in hiddenTrackIds && it.id !in trackIdsInSharedGroups }
        return SharedSurfaceFilterResult(
            sharedGroups = sharedGroups,
            sharedTrackers = sharedTrackers,
            hiddenTrackIds = hiddenTrackIds,
            hiddenGroupIds = hiddenGroupIds
        )
    }
}
