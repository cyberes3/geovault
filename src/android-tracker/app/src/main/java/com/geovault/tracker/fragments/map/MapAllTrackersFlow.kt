package com.geovault.tracker.fragments.map

import android.content.Context
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerRepository

internal data class MapAllTrackersCallbacks(
    val isAdded: () -> Boolean,
    val onEmpty: () -> Unit,
    val onHasTrackers: (List<Tracker>) -> Unit,
    val onHasGeometry: (List<Tracker>, Map<String, List<List<Double>>>) -> Unit
)

internal object MapAllTrackersFlow {
    /**
     * Load visible trackers and apply to map: get visibility → filter by hidden groups/trackers →
     * get trackers → apply with empty coords → get full geometry → apply again with coords.
     * Caller supplies callbacks for side effects (streaming, apply to map, etc.).
     */
    fun loadAllTrackersAndApply(context: Context, callbacks: MapAllTrackersCallbacks) {
        TrackerRepository.getMapVisibility(context) { visibility ->
            if (!callbacks.isAdded()) return@getMapVisibility
            val hiddenTrackIds = (visibility?.hidden_track_ids ?: emptyList()).toSet()
            TrackerRepository.getGroups(context, forceRefresh = false) { groupsList ->
                if (!callbacks.isAdded()) return@getGroups
                val hiddenGroupIds = (visibility?.hidden_group_ids ?: emptyList()).toSet()
                val groupsToHideFromMap = (groupsList ?: emptyList())
                    .filter { it.id in hiddenGroupIds || it.hidden_in_list == true }
                val trackIdsInHiddenGroups = groupsToHideFromMap
                    .flatMap { it.track_ids ?: emptyList() }
                    .toSet()
                val allHiddenTrackIds = hiddenTrackIds + trackIdsInHiddenGroups
                TrackerRepository.getTrackers(context, forceRefresh = false) { list ->
                    if (!callbacks.isAdded()) return@getTrackers
                    val allTrackers = list ?: emptyList()
                    val trackers = allTrackers.filter { it.id !in allHiddenTrackIds }
                    if (trackers.isEmpty()) {
                        callbacks.onEmpty()
                        return@getTrackers
                    }
                    callbacks.onHasTrackers(trackers)
                    TrackerRepository.getTrackersGeometry(
                        context,
                        trackers.map { it.id },
                        allData = true
                    ) { fullTrackers ->
                        if (!callbacks.isAdded()) return@getTrackersGeometry
                        val coordsById = mutableMapOf<String, List<List<Double>>>()
                        (fullTrackers ?: emptyList()).forEach { full ->
                            val coords = full.geometry?.coordinates ?: emptyList()
                            if (coords.isNotEmpty()) {
                                coordsById[full.id] = coords
                            }
                        }
                        callbacks.onHasGeometry(trackers, coordsById)
                    }
                }
            }
        }
    }

    /**
     * Decide if we can restore all-trackers from cache (last trackers + coords).
     * Returns (trackers, coordsById) to apply if restore is possible, null otherwise.
     */
    fun restoreAllTrackersFromCacheIfAvailable(
        lastTrackers: List<Tracker>?,
        lastCoordsById: Map<String, List<List<Double>>>?,
        multiTrackCoordsCache: Map<String, MutableList<List<Double>>>
    ): Pair<List<Tracker>, Map<String, List<List<Double>>>>? {
        val trackers = lastTrackers ?: return null
        val cachedCoordsById: Map<String, List<List<Double>>>? = when {
            !lastCoordsById.isNullOrEmpty() -> lastCoordsById
            multiTrackCoordsCache.isNotEmpty() -> multiTrackCoordsCache.mapValues { it.value.toList() }
            else -> null
        }
        val hasTailData = (cachedCoordsById?.values?.any { it.size >= 2 } == true) ||
            trackers.any { (it.geometry?.coordinates?.size ?: 0) >= 2 }
        if (!hasTailData) return null
        return trackers to (cachedCoordsById ?: emptyMap())
    }
}
