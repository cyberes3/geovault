package com.geovault.tracker.presentation

import com.geovault.tracker.Tracker
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.policy.TrackPointEvent
import org.maplibre.android.geometry.LatLngBounds

data class LiveActiveFitInput(
    val mode: TrackerMapDisplayMode,
    val runtimeRunning: Boolean,
    val followLockArmed: Boolean,
    val liveActiveFitEnabled: Boolean,
    val hasTrailPoints: Boolean,
    val isSelectedDefaultTracker: Boolean,
)

data class LiveActiveFitVisibility(
    val showButton: Boolean,
    val buttonEnabled: Boolean,
)

object TrackerMapLiveActiveFitPolicy {

    const val LIVE_ACTIVE_TRACKER_WINDOW_MS = 15 * 60 * 1000L

    fun resolveVisibility(input: LiveActiveFitInput): LiveActiveFitVisibility {
        if (input.runtimeRunning) {
            return LiveActiveFitVisibility(showButton = false, buttonEnabled = false)
        }
        val isMultiMode = input.mode == TrackerMapDisplayMode.ALL_QUEUE ||
            input.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER
        val singleTrackerVisible = !isMultiMode &&
            input.mode == TrackerMapDisplayMode.SINGLE_SESSION &&
            input.hasTrailPoints
        val available = isMultiMode || singleTrackerVisible
        if (!available || input.isSelectedDefaultTracker) {
            return LiveActiveFitVisibility(showButton = false, buttonEnabled = false)
        }
        val toggleEnabled = input.followLockArmed
        return LiveActiveFitVisibility(
            showButton = toggleEnabled,
            buttonEnabled = toggleEnabled,
        )
    }

    fun filterActiveTrails(
        allQueueTrailsByTracker: Map<String, List<QueuedLocation>>,
        remoteLastPoints: Map<String, TrackPointEvent>,
        trackers: List<Tracker>,
        nowMs: Long,
    ): Map<String, List<QueuedLocation>> {
        val activeIds = resolveActiveTrackerIds(
            allQueueTrailsByTracker = allQueueTrailsByTracker,
            remoteLastPoints = remoteLastPoints,
            trackers = trackers,
            nowMs = nowMs,
        )
        if (activeIds.isEmpty()) return allQueueTrailsByTracker
        return allQueueTrailsByTracker.filterKeys { it in activeIds }
    }

    fun activeTrailBounds(
        allQueueTrailsByTracker: Map<String, List<QueuedLocation>>,
        remoteLastPoints: Map<String, TrackPointEvent>,
        trackers: List<Tracker>,
        nowMs: Long,
    ): LatLngBounds? {
        val filtered = filterActiveTrails(
            allQueueTrailsByTracker = allQueueTrailsByTracker,
            remoteLastPoints = remoteLastPoints,
            trackers = trackers,
            nowMs = nowMs,
        )
        return TrackerMapStateTransforms.multiTrailBounds(filtered)
    }

    private fun resolveActiveTrackerIds(
        allQueueTrailsByTracker: Map<String, List<QueuedLocation>>,
        remoteLastPoints: Map<String, TrackPointEvent>,
        trackers: List<Tracker>,
        nowMs: Long,
    ): Set<String> {
        val allIds = allQueueTrailsByTracker.keys + remoteLastPoints.keys
        return allIds.filter { trackerId ->
            val lastUpdateMs = resolveTrackerLastUpdateMs(
                trackerId = trackerId,
                allQueueTrailsByTracker = allQueueTrailsByTracker,
                remoteLastPoints = remoteLastPoints,
                trackers = trackers,
            )
            lastUpdateMs != null && (nowMs - lastUpdateMs) <= LIVE_ACTIVE_TRACKER_WINDOW_MS
        }.toSet()
    }

    private fun resolveTrackerLastUpdateMs(
        trackerId: String,
        allQueueTrailsByTracker: Map<String, List<QueuedLocation>>,
        remoteLastPoints: Map<String, TrackPointEvent>,
        trackers: List<Tracker>,
    ): Long? {
        val remoteMs = remoteLastPoints[trackerId]?.timestampMs
        if (remoteMs != null && remoteMs > 0L) return remoteMs

        val trailMs = allQueueTrailsByTracker[trackerId]?.lastOrNull()?.time
        if (trailMs != null && trailMs > 0L) return trailMs

        val tracker = trackers.firstOrNull { it.id == trackerId }
        return tracker?.updated_at
    }
}
