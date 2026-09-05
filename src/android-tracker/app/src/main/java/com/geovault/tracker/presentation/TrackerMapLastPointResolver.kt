package com.geovault.tracker.presentation

import com.geovault.tracker.Tracker
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.services.TrackingRuntimeSnapshot

data class TrackerMapResolvedPoint(
    val latitude: Double,
    val longitude: Double,
    val lastUpdatedMs: Long?,
    val accuracyMeters: Float?,
)

object TrackerMapLastPointResolver {
    fun resolve(
        snapshot: TrackerMapSessionSnapshot,
        trackerId: String,
        tracker: Tracker?,
    ): TrackerMapResolvedPoint? {
        return resolve(
            state = snapshot.uiState.copy(
                trail = snapshot.singleTrail,
                allQueueTrailsByTracker = snapshot.renderTrailsByTracker,
                remoteLastPoints = snapshot.acceptedRemoteLastPoints,
            ),
            trackerId = trackerId,
            tracker = tracker,
            acceptedRemoteTrackerIds = snapshot.plan.acceptedRemoteTrackerIds,
        )
    }

    fun resolve(
        state: TrackerMapUiState,
        trackerId: String,
        tracker: Tracker?,
        acceptedRemoteTrackerIds: Set<String>,
    ): TrackerMapResolvedPoint? {
        val normalizedId = trackerId.trim()
        if (normalizedId.isEmpty()) return null
        val acceptedRemoteIds = acceptedRemoteTrackerIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val eff = TrackerMapDisplayIds.effectiveDisplayedTrackerId(state)
        val runtimePoint = state.runtime.takeIf {
            it.localRecordingActive &&
                normalizedId == it.locallyRecordedTrackerId &&
                it.lastTrackedLatitude != null &&
                it.lastTrackedLongitude != null
        }?.let {
            PointCandidate(
                latitude = it.lastTrackedLatitude ?: return null,
                longitude = it.lastTrackedLongitude ?: return null,
                lastUpdatedMs = positiveTimestampMs(it.lastTrackedTimestampMs),
                accuracyMeters = it.lastAccuracyMeters,
            )
        }
        val remotePoint = if (normalizedId in acceptedRemoteIds) {
            state.remoteLastPoints[normalizedId]
        } else {
            null
        }
        val singleTrailPoint = state.trail.lastOrNull()
            ?.takeIf {
                normalizedId == eff || normalizedId == state.runtime.selectedTrackerId.trim()
            }
        val multiTrailPoint = state.allQueueTrailsByTracker[normalizedId]?.lastOrNull()
        val selectedPoint = freshestPoint(
            runtimePoint = runtimePoint,
            remotePoint = remotePoint,
            singleTrailPoint = singleTrailPoint,
            multiTrailPoint = multiTrailPoint,
            tracker = tracker,
            trackerLastPoint = tracker?.last_point,
        ) ?: return null
        return TrackerMapResolvedPoint(
            latitude = selectedPoint.latitude,
            longitude = selectedPoint.longitude,
            lastUpdatedMs = selectedPoint.lastUpdatedMs,
            accuracyMeters = selectedPoint.accuracyMeters,
        )
    }

    private fun freshestPoint(
        runtimePoint: PointCandidate?,
        remotePoint: TrackPointEvent?,
        singleTrailPoint: QueuedLocation?,
        multiTrailPoint: QueuedLocation?,
        tracker: Tracker?,
        trackerLastPoint: List<Double>?,
    ): PointCandidate? {
        val candidates = listOfNotNull(
            runtimePoint,
            remotePoint?.let {
                PointCandidate(
                    latitude = it.lat,
                    longitude = it.lon,
                    lastUpdatedMs = positiveTimestampMs(it.timestampMs),
                    accuracyMeters = it.accuracyMeters,
                )
            },
            singleTrailPoint?.toPointCandidate(),
            multiTrailPoint?.toPointCandidate(),
            trackerLastPoint?.takeIf { it.size >= 2 }?.let {
                PointCandidate(
                    latitude = it[1],
                    longitude = it[0],
                    lastUpdatedMs = rosterLastUpdatedMs(tracker, it),
                    accuracyMeters = null,
                )
            },
        )
        return candidates.maxWithOrNull(compareBy<PointCandidate> { it.lastUpdatedMs ?: Long.MIN_VALUE })
    }

    private fun rosterLastUpdatedMs(tracker: Tracker?, lastPoint: List<Double>): Long? {
        val lastPointTs = lastPoint.getOrNull(2)?.let(::normalizeExternalTimestampToMs)
        val paramsTs = tracker?.point_params
            ?.lastOrNull()
            ?.entries
            ?.asSequence()
            ?.filter { it.key.contains("timestamp", ignoreCase = true) }
            ?.mapNotNull { TrackerMapSessionWindowPolicy.normalizeTimestampToMs(it.value)?.takeIf { ts -> ts > 0L } }
            ?.maxOrNull()
        return listOfNotNull(lastPointTs, paramsTs).maxOrNull()
            ?: normalizeExternalTimestampToMs(tracker?.updated_at)
    }

    private fun positiveTimestampMs(value: Number?): Long? {
        return value?.toLong()?.takeIf { it > 0L }
    }

    private fun normalizeExternalTimestampToMs(value: Number?): Long? {
        val raw = value?.toLong() ?: return null
        if (raw < 1_000_000_000L) return null
        return TrackerMapSessionWindowPolicy.normalizeTimestampToMs(raw)?.takeIf { it > 0L }
    }

    private fun QueuedLocation.toPointCandidate(): PointCandidate {
        return PointCandidate(
            latitude = latitude,
            longitude = longitude,
            lastUpdatedMs = positiveTimestampMs(time),
            accuracyMeters = accuracy,
        )
    }

    private data class PointCandidate(
        val latitude: Double,
        val longitude: Double,
        val lastUpdatedMs: Long?,
        val accuracyMeters: Float?,
    )
}
