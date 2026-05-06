package com.geovault.tracker.presentation

/**
 * Remote points are accepted only for the current projected subscription targets.
 * Foreground service state can lag during target changes, so active ids are only
 * retained when they still match the current projection.
 */
object TrackerMapRemoteAcceptancePolicy {
    fun mergedAcceptedRemoteTrackerIds(
        streamTargetIds: Set<String>,
        activeStreamedTrackerIds: Set<String>,
    ): Set<String> {
        val projectedIds = streamTargetIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val activeIds = activeStreamedTrackerIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        return projectedIds + (activeIds intersect projectedIds)
    }
}
