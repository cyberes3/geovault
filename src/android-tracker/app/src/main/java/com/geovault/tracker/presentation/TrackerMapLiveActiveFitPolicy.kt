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

    /**
     * Rolling time window for "recently active" trackers when group / all-queue live-active-fit
     * uses activity filtering (settings: auto-fit only active trackers in group mode, default on).
     */
    const val LIVE_ACTIVE_TRACKER_WINDOW_MS = 10 * 60 * 1000L

    /**
     * GROUP / ALL_QUEUE live-active-fit bounds: either the recent-activity subset only, or the full
     * union of trail bounds, accepted remote heads, and roster `last_point` (same composition as
     * the non-live-active multi-mode fit in [TrackerMapViewModel.trailBoundsOrNull]).
     */
    fun resolveGroupLiveFitBounds(
        filterToActiveOnly: Boolean,
        allQueueTrailsByTracker: Map<String, List<QueuedLocation>>,
        remoteLastPoints: Map<String, TrackPointEvent>,
        acceptedRemoteTrackerIds: Set<String>,
        trackers: List<Tracker>,
        nowMs: Long,
        multiTrailBounds: LatLngBounds?,
        remotePointBounds: LatLngBounds?,
        rosterLastPointBounds: LatLngBounds?,
    ): LatLngBounds? {
        if (filterToActiveOnly) {
            return when (
                val result = activeTrailBoundsResult(
                    allQueueTrailsByTracker = allQueueTrailsByTracker,
                    remoteLastPoints = remoteLastPoints,
                    acceptedRemoteTrackerIds = acceptedRemoteTrackerIds,
                    trackers = trackers,
                    nowMs = nowMs,
                )
            ) {
                is LiveActiveTrailBoundsResult.Active -> result.bounds
                LiveActiveTrailBoundsResult.NoActiveTrackers -> null
            }
        }
        return TrackerMapStateTransforms.mergeBounds(
            TrackerMapStateTransforms.mergeBounds(multiTrailBounds, remotePointBounds),
            rosterLastPointBounds,
        )
    }

    /**
     * Resolves the "lock armed" gate for the secondary live-active-fit FAB.
     *
     * The secondary FAB now exists only in SINGLE_SESSION (ALL_QUEUE and GROUP_PLACEHOLDER both
     * have their lock FAB own live-active-fit directly), so the gate is simply whether the
     * displayed single tracker is selection-locked.
     */
    fun resolveLockArmed(singleTrackerLocked: Boolean): Boolean = singleTrackerLocked

    fun resolveVisibility(input: LiveActiveFitInput): LiveActiveFitVisibility {
        val singleTrackerVisible = input.mode == TrackerMapDisplayMode.SINGLE_SESSION &&
            input.hasTrailPoints
        if (!singleTrackerVisible || input.isSelectedDefaultTracker) {
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
        val pinnedRemoteIds = acceptedRemoteIds.filter { trackerId ->
            allQueueTrailsByTracker.containsKey(trackerId) || filteredRemoteLastPoints.containsKey(trackerId)
        }
        val boundsIds = activeIds + pinnedRemoteIds
        if (boundsIds.isEmpty()) return LiveActiveTrailBoundsResult.NoActiveTrackers
        val activeTrails = allQueueTrailsByTracker.filterKeys { it in boundsIds }
        val activeRemoteHeads = filteredRemoteLastPoints.filterKeys { it in boundsIds }
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
