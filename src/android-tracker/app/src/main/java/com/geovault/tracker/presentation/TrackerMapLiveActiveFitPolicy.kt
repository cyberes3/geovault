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

sealed class LiveActiveTrailBoundsResult {
    data class Active(val bounds: LatLngBounds) : LiveActiveTrailBoundsResult()
    data object NoActiveTrackers : LiveActiveTrailBoundsResult()
}

object TrackerMapLiveActiveFitPolicy {

    const val LIVE_ACTIVE_TRACKER_WINDOW_MS = 15 * 60 * 1000L

    fun resolveLockArmed(
        singleTrackerMapView: Boolean,
        singleTrackerLocked: Boolean,
        multiFollowLockArmed: Boolean,
    ): Boolean {
        return if (singleTrackerMapView) singleTrackerLocked else multiFollowLockArmed
    }

    fun resolveVisibility(input: LiveActiveFitInput): LiveActiveFitVisibility {
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
        acceptedRemoteTrackerIds: Set<String> = remoteLastPoints.keys,
        trackers: List<Tracker>,
        nowMs: Long,
    ): Map<String, List<QueuedLocation>> {
        val acceptedRemoteIds = acceptedRemoteTrackerIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val filteredRemoteLastPoints = remoteLastPoints.filterKeys { it.trim() in acceptedRemoteIds }
        val activeIds = resolveActiveTrackerIds(
            allQueueTrailsByTracker = allQueueTrailsByTracker,
            remoteLastPoints = filteredRemoteLastPoints,
            trackers = trackers,
            nowMs = nowMs,
        )
        if (activeIds.isEmpty()) return emptyMap()
        return allQueueTrailsByTracker.filterKeys { it in activeIds }
    }

    fun activeTrailBounds(
        allQueueTrailsByTracker: Map<String, List<QueuedLocation>>,
        remoteLastPoints: Map<String, TrackPointEvent>,
        acceptedRemoteTrackerIds: Set<String> = remoteLastPoints.keys,
        trackers: List<Tracker>,
        nowMs: Long,
    ): LatLngBounds? {
        return when (val result = activeTrailBoundsResult(
            allQueueTrailsByTracker = allQueueTrailsByTracker,
            remoteLastPoints = remoteLastPoints,
            acceptedRemoteTrackerIds = acceptedRemoteTrackerIds,
            trackers = trackers,
            nowMs = nowMs,
        )) {
            is LiveActiveTrailBoundsResult.Active -> result.bounds
            LiveActiveTrailBoundsResult.NoActiveTrackers -> null
        }
    }

    fun activeTrailBoundsResult(
        allQueueTrailsByTracker: Map<String, List<QueuedLocation>>,
        remoteLastPoints: Map<String, TrackPointEvent>,
        acceptedRemoteTrackerIds: Set<String> = remoteLastPoints.keys,
        trackers: List<Tracker>,
        nowMs: Long,
    ): LiveActiveTrailBoundsResult {
        val acceptedRemoteIds = acceptedRemoteTrackerIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val filteredRemoteLastPoints = remoteLastPoints.filterKeys { it.trim() in acceptedRemoteIds }
        val activeIds = resolveActiveTrackerIds(
            allQueueTrailsByTracker = allQueueTrailsByTracker,
            remoteLastPoints = filteredRemoteLastPoints,
            trackers = trackers,
            nowMs = nowMs,
        )
        if (activeIds.isEmpty()) return LiveActiveTrailBoundsResult.NoActiveTrackers
        val activeTrails = allQueueTrailsByTracker.filterKeys { it in activeIds }
        val activeRemoteHeads = filteredRemoteLastPoints.filterKeys { it in activeIds }
        val trailBounds = TrackerMapStateTransforms.multiTrailBounds(activeTrails)
        val remoteBounds = TrackerMapStateTransforms.remoteLastPointBounds(activeRemoteHeads)
        val bounds = TrackerMapStateTransforms.mergeBounds(trailBounds, remoteBounds)
            ?: return LiveActiveTrailBoundsResult.NoActiveTrackers
        return LiveActiveTrailBoundsResult.Active(bounds)
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
        val remoteMs = TrackerMapSessionWindowPolicy.normalizeTimestampToMs(remoteLastPoints[trackerId]?.timestampMs)
        val trailMs = TrackerMapSessionWindowPolicy.normalizeTimestampToMs(allQueueTrailsByTracker[trackerId]?.lastOrNull()?.time)
        val tracker = trackers.firstOrNull { it.id == trackerId }
        val trackerMs = TrackerMapSessionWindowPolicy.normalizeTimestampToMs(tracker?.updated_at)
        return listOfNotNull(remoteMs, trailMs, trackerMs)
            .filter { it > 0L }
            .maxOrNull()
    }
}
