package com.geovault.tracker.presentation

import com.geovault.common.maps.core.geoVaultLatLngBoundsForPoints
import com.geovault.common.maps.core.isValidMapLibreGeographicLatLng
import com.geovault.tracker.Tracker
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds

data class TrackerMapGroupBoundsInput(
    val visibleTrackerIds: Set<String>,
    val liveActiveFitEnabled: Boolean,
    val fitOnlyActiveTrackers: Boolean,
    val trailsByTracker: Map<String, List<QueuedLocation>>,
    val remoteLastPoints: Map<String, TrackPointEvent>,
    val acceptedRemoteTrackerIds: Set<String>,
    val trackers: List<Tracker>,
    val nowMs: Long,
    val runtime: TrackingRuntimeSnapshot = TrackingRuntimeSnapshot(),
)

sealed class TrackerMapGroupBoundsStrategy {
    /** Lock off: union drawn trails and the one resolved head per visible tracker. */
    data object AllVisible : TrackerMapGroupBoundsStrategy()

    /** Lock on with "fit all trackers" setting: same composition as [AllVisible]. */
    data object AllVisibleWhileLocked : TrackerMapGroupBoundsStrategy()

    /** Lock on with "fit only active trackers": recent activity within [ACTIVE_TRACKER_WINDOW_MS]. */
    data object ActiveOnly : TrackerMapGroupBoundsStrategy()
}

/**
 * Outcome of [TrackerMapGroupBoundsResolver.resolveOrHold], distinguishing *why* no bounds were
 * resolved so callers know whether it's safe to fall back to some other bounds source.
 */
sealed class TrackerMapGroupBoundsResolution {
    data class Bounds(val bounds: LatLngBounds) : TrackerMapGroupBoundsResolution()

    /**
     * [TrackerMapGroupBoundsStrategy.ActiveOnly] found zero qualifying trackers. Falling back to
     * an all-tracker/single-point bounds here would silently override the "only show active
     * trackers" intent the moment the roster goes quiet -- callers should hold the camera (apply
     * no directive) instead of substituting an unrelated fit.
     */
    data object Hold : TrackerMapGroupBoundsResolution()

    /** Genuinely nothing to show (e.g. empty roster, lock off) -- safe to fall back. */
    data object NoBounds : TrackerMapGroupBoundsResolution()
}

/**
 * Single authority for GROUP_PLACEHOLDER / ALL_QUEUE map bounds.
 *
 * Replaces split logic between [TrackerMapViewModel] and [TrackerMapLiveActiveFitPolicy] so lock-on
 * active fit can frame roster live positions immediately and never falls back to stale unions.
 */
object TrackerMapGroupBoundsResolver {

    const val ACTIVE_TRACKER_WINDOW_MS = 10 * 60 * 1000L

    fun strategy(input: TrackerMapGroupBoundsInput): TrackerMapGroupBoundsStrategy {
        if (!input.liveActiveFitEnabled) {
            return TrackerMapGroupBoundsStrategy.AllVisible
        }
        return if (input.fitOnlyActiveTrackers) {
            TrackerMapGroupBoundsStrategy.ActiveOnly
        } else {
            TrackerMapGroupBoundsStrategy.AllVisibleWhileLocked
        }
    }

    fun resolve(input: TrackerMapGroupBoundsInput): LatLngBounds? {
        val normalizedInput = input.normalizedToVisibleTrackers()
        return when (strategy(normalizedInput)) {
            TrackerMapGroupBoundsStrategy.AllVisible,
            TrackerMapGroupBoundsStrategy.AllVisibleWhileLocked,
            -> resolveAllVisible(normalizedInput)
            TrackerMapGroupBoundsStrategy.ActiveOnly -> resolveActiveOnly(normalizedInput)
        }
    }

    /**
     * Same resolution as [resolve], but distinguishes an [TrackerMapGroupBoundsResolution.Hold]
     * (ActiveOnly with zero qualifying trackers) from a genuine [TrackerMapGroupBoundsResolution.NoBounds]
     * so callers know whether falling back to some other bounds source is safe.
     */
    fun resolveOrHold(input: TrackerMapGroupBoundsInput): TrackerMapGroupBoundsResolution {
        val normalizedInput = input.normalizedToVisibleTrackers()
        val resolvedStrategy = strategy(normalizedInput)
        val bounds = when (resolvedStrategy) {
            TrackerMapGroupBoundsStrategy.AllVisible,
            TrackerMapGroupBoundsStrategy.AllVisibleWhileLocked,
            -> resolveAllVisible(normalizedInput)
            TrackerMapGroupBoundsStrategy.ActiveOnly -> resolveActiveOnly(normalizedInput)
        }
        if (bounds != null) return TrackerMapGroupBoundsResolution.Bounds(bounds)
        return if (resolvedStrategy == TrackerMapGroupBoundsStrategy.ActiveOnly) {
            TrackerMapGroupBoundsResolution.Hold
        } else {
            TrackerMapGroupBoundsResolution.NoBounds
        }
    }

    private fun resolveAllVisible(input: TrackerMapGroupBoundsInput): LatLngBounds? {
        return boundsForTrackerIds(input, input.visibleTrackerIds)
    }

    private fun resolveActiveOnly(input: TrackerMapGroupBoundsInput): LatLngBounds? {
        val acceptedRemoteIds = input.acceptedRemoteTrackerIds
            .map { it.trim() }
            .filter { it.isNotEmpty() && it in input.visibleTrackerIds }
            .toSet()
        val filteredRemoteLastPoints = input.remoteLastPoints.filterKeys { it.trim() in acceptedRemoteIds }
        val visibleIds = input.visibleTrackerIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val activeIds = resolveActiveTrackerIds(
            candidateTrackerIds = visibleIds + input.trailsByTracker.keys + filteredRemoteLastPoints.keys,
            trailsByTracker = input.trailsByTracker,
            remoteLastPoints = filteredRemoteLastPoints,
            trackers = input.trackers,
            nowMs = input.nowMs,
        )
        val pinnedRemoteIds = acceptedRemoteIds.filter { trackerId ->
            input.trailsByTracker.containsKey(trackerId) || filteredRemoteLastPoints.containsKey(trackerId)
        }
        val boundsIds = activeIds + pinnedRemoteIds
        if (boundsIds.isEmpty()) return null
        return boundsForTrackerIds(input, boundsIds)
    }

    private fun boundsForTrackerIds(
        input: TrackerMapGroupBoundsInput,
        trackerIds: Set<String>,
    ): LatLngBounds? {
        val trails = input.trailsByTracker.filterKeys { it.trim() in trackerIds }
        val trailBounds = TrackerMapStateTransforms.multiTrailBounds(trails)
        val headBounds = resolvedHeadBounds(input, trackerIds)
        return TrackerMapStateTransforms.mergeBounds(trailBounds, headBounds)
    }

    private fun resolvedHeadBounds(
        input: TrackerMapGroupBoundsInput,
        trackerIds: Set<String>,
    ): LatLngBounds? {
        val points = trackerIds.mapNotNull { trackerId ->
            val tracker = input.trackers.firstOrNull { it.id.trim() == trackerId.trim() }
            val resolved = TrackerMapLastPointResolver.resolve(
                state = TrackerMapUiState(
                    allQueueTrailsByTracker = input.trailsByTracker,
                    remoteLastPoints = input.remoteLastPoints,
                    displayedTrackerId = trackerId,
                    runtime = input.runtime,
                ),
                trackerId = trackerId,
                tracker = tracker,
                acceptedRemoteTrackerIds = input.acceptedRemoteTrackerIds,
            ) ?: return@mapNotNull null
            if (!isValidMapLibreGeographicLatLng(resolved.latitude, resolved.longitude)) {
                return@mapNotNull null
            }
            LatLng(resolved.latitude, resolved.longitude)
        }
        return geoVaultLatLngBoundsForPoints(points)
    }

    private fun resolveActiveTrackerIds(
        candidateTrackerIds: Set<String>,
        trailsByTracker: Map<String, List<QueuedLocation>>,
        remoteLastPoints: Map<String, TrackPointEvent>,
        trackers: List<Tracker>,
        nowMs: Long,
    ): Set<String> {
        return candidateTrackerIds.filter { trackerId ->
            isTrackerActive(
                trackerId = trackerId,
                trailsByTracker = trailsByTracker,
                remoteLastPoints = remoteLastPoints,
                trackers = trackers,
                nowMs = nowMs,
            )
        }.toSet()
    }

    /**
     * Single-tracker version of the same "recent activity within [ACTIVE_TRACKER_WINDOW_MS]"
     * definition [resolveActiveTrackerIds] applies across a roster, exposed for callers that need
     * an alive/dead read on one specific tracker (e.g. deciding camera behavior when a tracker is
     * opened) rather than a group bounds fit.
     */
    fun isTrackerActive(
        trackerId: String,
        trailsByTracker: Map<String, List<QueuedLocation>>,
        remoteLastPoints: Map<String, TrackPointEvent>,
        trackers: List<Tracker>,
        nowMs: Long,
    ): Boolean {
        val lastUpdateMs = resolveTrackerLastUpdateMs(
            trackerId = trackerId,
            trailsByTracker = trailsByTracker,
            remoteLastPoints = remoteLastPoints,
            trackers = trackers,
        )
        return lastUpdateMs != null && (nowMs - lastUpdateMs) <= ACTIVE_TRACKER_WINDOW_MS
    }

    private fun resolveTrackerLastUpdateMs(
        trackerId: String,
        trailsByTracker: Map<String, List<QueuedLocation>>,
        remoteLastPoints: Map<String, TrackPointEvent>,
        trackers: List<Tracker>,
    ): Long? {
        val tracker = trackers.firstOrNull { it.id.trim() == trackerId.trim() }
        val resolved = TrackerMapLastPointResolver.resolve(
            state = TrackerMapUiState(
                allQueueTrailsByTracker = trailsByTracker,
                remoteLastPoints = remoteLastPoints,
                displayedTrackerId = trackerId,
            ),
            trackerId = trackerId,
            tracker = tracker,
            acceptedRemoteTrackerIds = remoteLastPoints.keys,
        )
        return resolved?.lastUpdatedMs
    }

    private fun TrackerMapGroupBoundsInput.normalizedToVisibleTrackers(): TrackerMapGroupBoundsInput {
        val visibleIds = visibleTrackerIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        return copy(
            visibleTrackerIds = visibleIds,
            trailsByTracker = trailsByTracker
                .mapKeys { it.key.trim() }
                .filterKeys { it in visibleIds },
            remoteLastPoints = remoteLastPoints
                .mapKeys { it.key.trim() }
                .filterKeys { it in visibleIds },
            acceptedRemoteTrackerIds = acceptedRemoteTrackerIds
                .map { it.trim() }
                .filter { it.isNotEmpty() && it in visibleIds }
                .toSet(),
        )
    }

}
