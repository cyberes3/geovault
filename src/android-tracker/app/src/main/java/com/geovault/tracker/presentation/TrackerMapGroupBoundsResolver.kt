package com.geovault.tracker.presentation

import com.geovault.common.maps.core.geoVaultLatLngBoundsForPoints
import com.geovault.common.maps.core.isValidMapLibreGeographicLatLng
import com.geovault.tracker.Tracker
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.policy.TrackPointEvent
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
)

sealed class TrackerMapGroupBoundsStrategy {
    /** Lock off: union trails, remote heads, and every visible roster last_point. */
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
        val trailBounds = TrackerMapStateTransforms.multiTrailBounds(input.trailsByTracker)
        val remoteBounds = TrackerMapStateTransforms.remoteLastPointBounds(input.remoteLastPoints)
        val rosterBounds = rosterLastPointBounds(
            visibleTrackerIds = input.visibleTrackerIds,
            trackerIds = input.visibleTrackerIds,
            trackers = input.trackers,
        )
        return TrackerMapStateTransforms.mergeBounds(
            TrackerMapStateTransforms.mergeBounds(trailBounds, remoteBounds),
            rosterBounds,
        )
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
        val activeTrails = input.trailsByTracker.filterKeys { it in boundsIds }
        val activeRemoteHeads = filteredRemoteLastPoints.filterKeys { it in boundsIds }
        val trailBounds = TrackerMapStateTransforms.multiTrailBounds(activeTrails)
        val remoteBounds = TrackerMapStateTransforms.remoteLastPointBounds(activeRemoteHeads)
        val rosterBounds = rosterLastPointBounds(
            visibleTrackerIds = visibleIds,
            trackerIds = boundsIds,
            trackers = input.trackers,
        )
        return TrackerMapStateTransforms.mergeBounds(
            TrackerMapStateTransforms.mergeBounds(trailBounds, remoteBounds),
            rosterBounds,
        )
    }

    private fun rosterLastPointBounds(
        visibleTrackerIds: Set<String>,
        trackerIds: Set<String>,
        trackers: List<Tracker>,
    ): LatLngBounds? {
        if (visibleTrackerIds.isEmpty() || trackerIds.isEmpty()) return null
        val latLngs = trackers
            .asSequence()
            .filter { it.id.trim() in visibleTrackerIds && it.id.trim() in trackerIds }
            .mapNotNull { tracker -> tracker.lastPointLatLngOrNull() }
            .toList()
        return geoVaultLatLngBoundsForPoints(latLngs)
    }

    private fun Tracker.lastPointLatLngOrNull(): LatLng? {
        val coord = last_point ?: return null
        val lon = coord.getOrNull(0) ?: return null
        val lat = coord.getOrNull(1) ?: return null
        if (!isValidMapLibreGeographicLatLng(lat, lon)) return null
        return LatLng(lat, lon)
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
        val remoteMs = TrackerMapSessionWindowPolicy.normalizeTimestampToMs(remoteLastPoints[trackerId]?.timestampMs)
        val trailMs = TrackerMapSessionWindowPolicy.normalizeTimestampToMs(trailsByTracker[trackerId]?.lastOrNull()?.time)
        val tracker = trackers.firstOrNull { it.id.trim() == trackerId.trim() }
        val trackerDataMs = tracker?.lastDataTimestampMsOrNull()
        return listOfNotNull(remoteMs, trailMs, trackerDataMs)
            .filter { it > 0L }
            .maxOrNull()
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

    private fun Tracker.lastDataTimestampMsOrNull(): Long? {
        val lastPointMs = last_point
            ?.getOrNull(2)
            ?.let(TrackerMapSessionWindowPolicy::normalizeTimestampToMs)
        val paramsMs = point_params
            ?.lastOrNull()
            ?.entries
            ?.asSequence()
            ?.filter { it.key.contains("timestamp", ignoreCase = true) }
            ?.mapNotNull { TrackerMapSessionWindowPolicy.normalizeTimestampToMs(it.value) }
            ?.maxOrNull()
        return listOfNotNull(lastPointMs, paramsMs)
            .filter { it > 0L }
            .maxOrNull()
    }
}
