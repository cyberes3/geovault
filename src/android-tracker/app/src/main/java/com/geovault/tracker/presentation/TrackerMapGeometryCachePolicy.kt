package com.geovault.tracker.presentation

import com.geovault.tracker.Tracker

/**
 * Strips persisted geometry from in-memory tracker snapshots so a subsequent
 * `GET geometry` cannot be short-circuited by preload paths serving coordinates
 * filtered under a previous `recent_data_window`.
 */
object TrackerMapGeometryCachePolicy {

    fun stripGeometry(trackers: List<Tracker>, trackerIds: Set<String>): List<Tracker> {
        if (trackerIds.isEmpty()) return trackers
        val normalizedIds = trackerIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (normalizedIds.isEmpty()) return trackers
        return trackers.map { tracker ->
            if (tracker.id !in normalizedIds) {
                tracker
            } else {
                tracker.copy(
                    geometry = null,
                    point_params = null,
                    bbox = null,
                )
            }
        }
    }
}
