package com.geovault.tracker.presentation

import com.geovault.tracker.Tracker
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.policy.TrackPointEvent

data class TrackerMapResolvedPoint(
    val latitude: Double,
    val longitude: Double,
    val lastUpdatedMs: Long?,
    val accuracyMeters: Float?,
)

object TrackerMapLastPointResolver {
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
                normalizedId == it.selectedTrackerId.trim() &&
                it.lastTrackedLatitude != null &&
                it.lastTrackedLongitude != null
        }
        val remotePoint = if (normalizedId in acceptedRemoteIds) {
            state.remoteLastPoints[normalizedId]
        } else {
            null
        }
        val singleTrailPoint = state.trail.lastOrNull()
            ?.takeIf {
                normalizedId == eff || normalizedId == state.runtime.selectedTrackerId
            }
        val multiTrailPoint = state.allQueueTrailsByTracker[normalizedId]?.lastOrNull()
        val trackerLastPoint = tracker?.last_point
        val selectedPoint = runtimePoint?.let {
            PointCandidate(
                latitude = it.lastTrackedLatitude ?: return null,
                longitude = it.lastTrackedLongitude ?: return null,
                lastUpdatedMs = positiveTimestampMs(it.lastTrackedTimestampMs),
                accuracyMeters = it.lastAccuracyMeters,
            )
        } ?: freshestNonRuntimePoint(
            remotePoint = remotePoint,
            singleTrailPoint = singleTrailPoint,
            multiTrailPoint = multiTrailPoint,
            tracker = tracker,
            trackerLastPoint = trackerLastPoint,
        ) ?: return null
        return TrackerMapResolvedPoint(
            latitude = selectedPoint.latitude,
            longitude = selectedPoint.longitude,
            lastUpdatedMs = selectedPoint.lastUpdatedMs,
            accuracyMeters = selectedPoint.accuracyMeters,
        )
    }

    private fun freshestNonRuntimePoint(
        remotePoint: TrackPointEvent?,
        singleTrailPoint: QueuedLocation?,
        multiTrailPoint: QueuedLocation?,
        tracker: Tracker?,
        trackerLastPoint: List<Double>?,
    ): PointCandidate? {
        val candidates = listOfNotNull(
            remotePoint?.let {
                PointCandidate(
                    latitude = it.lat,
                    longitude = it.lon,
                    lastUpdatedMs = positiveTimestampMs(it.timestampMs),
                    accuracyMeters = it.accuracyMeters,
                )
            },
            singleTrailPoint?.let {
                PointCandidate(
                    latitude = it.latitude,
                    longitude = it.longitude,
                    lastUpdatedMs = positiveTimestampMs(it.time),
                    accuracyMeters = it.accuracy,
                )
            },
            multiTrailPoint?.let {
                PointCandidate(
                    latitude = it.latitude,
                    longitude = it.longitude,
                    lastUpdatedMs = positiveTimestampMs(it.time),
                    accuracyMeters = it.accuracy,
                )
            },
            trackerLastPoint?.takeIf { it.size >= 2 }?.let {
                PointCandidate(
                    latitude = it[1],
                    longitude = it[0],
                    lastUpdatedMs = it.getOrNull(2)?.let(::normalizeExternalTimestampToMs)
                        ?: normalizeExternalTimestampToMs(tracker?.updated_at),
                    accuracyMeters = null,
                )
            }
        )
        return candidates.maxWithOrNull(compareBy<PointCandidate> { it.lastUpdatedMs ?: Long.MIN_VALUE })
    }

    private fun positiveTimestampMs(value: Number?): Long? {
        return value?.toLong()?.takeIf { it > 0L }
    }

    private fun normalizeExternalTimestampToMs(value: Number?): Long? {
        val raw = value?.toLong() ?: return null
        if (raw < 1_000_000_000L) return null
        return TrackerMapSessionWindowPolicy.normalizeTimestampToMs(raw)?.takeIf { it > 0L }
    }

    private data class PointCandidate(
        val latitude: Double,
        val longitude: Double,
        val lastUpdatedMs: Long?,
        val accuracyMeters: Float?,
    )
}
