package com.geovault.tracker.map

import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.domain.TrackPoint
import com.geovault.tracker.presentation.TrackerMapSessionSnapshot

@ConsistentCopyVisibility
data class TrailView internal constructor(
    val singleTrail: List<QueuedLocation> = emptyList(),
    val tracksByTrackerId: Map<String, List<QueuedLocation>> = emptyMap(),
    val remoteLastPoints: Map<String, TrackPoint> = emptyMap(),
    val degradedTrackerIds: Set<String> = emptySet(),
    internal val snapshot: TrackerMapSessionSnapshot? = null,
) {
    val hasTrailPoints: Boolean
        get() = singleTrail.isNotEmpty() || tracksByTrackerId.values.any { it.isNotEmpty() }

    val hasDegradedTrails: Boolean
        get() = degradedTrackerIds.isNotEmpty()

    fun withoutTracker(trackerId: String, clearSingleTrail: Boolean): TrailView {
        val id = trackerId.trim()
        if (id.isEmpty()) return this
        return copy(
            singleTrail = if (clearSingleTrail) emptyList() else singleTrail,
            tracksByTrackerId = tracksByTrackerId - id,
            remoteLastPoints = remoteLastPoints - id,
            degradedTrackerIds = degradedTrackerIds - id,
        )
    }
}
