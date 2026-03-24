package com.geovault.tracker.fragments

import com.geovault.tracker.Group
import com.geovault.tracker.Tracker
import javax.inject.Inject

data class SharedSurfaceFilterResult(
    val sharedGroups: List<Group>,
    val sharedTrackers: List<Tracker>
)

class SharedSurfaceFilterUseCase @Inject constructor() {
    fun filter(
        groups: List<Group>,
        trackers: List<Tracker>
    ): SharedSurfaceFilterResult {
        val sharedGroups = groups
            .filter { it.is_owner != true && it.visibility == "shared" }
            .filter { it.is_accepted == true }
        val trackIdsInSharedGroups = sharedGroups
            .flatMap { it.track_ids ?: emptyList() }
            .toSet()
        val sharedTrackers = trackers
            .filter { !it.isOwner() && (it.visibility == "shared" || it.visibility == "public") }
            .filter { it.id !in trackIdsInSharedGroups }
        return SharedSurfaceFilterResult(
            sharedGroups = sharedGroups,
            sharedTrackers = sharedTrackers
        )
    }
}
