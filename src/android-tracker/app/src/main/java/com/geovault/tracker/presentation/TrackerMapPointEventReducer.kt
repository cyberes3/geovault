package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointSource

data class TrackerMapPointReductionInput(
    val state: TrackerMapUiState,
    val point: TrackPointEvent,
    val trailPointLimit: Int,
    val sessionPlan: TrackerMapStreamingPlan,
)

data class TrackerMapPointReductionResult(
    val acceptedBySourcePolicy: Boolean,
    val shouldUpdateUiState: Boolean,
    val nextState: TrackerMapUiState,
)

object TrackerMapPointEventReducer {
    fun reduce(input: TrackerMapPointReductionInput): TrackerMapPointReductionResult {
        val state = input.state
        val point = input.point
        val route = TrackerMapPointRouter.route(point, input.sessionPlan)
        if (!route.accepted) {
            return TrackerMapPointReductionResult(
                acceptedBySourcePolicy = false,
                shouldUpdateUiState = false,
                nextState = state,
            )
        }

        return when (point.source) {
            TrackPointSource.REMOTE_STREAM -> reduceRemote(input, route)
            TrackPointSource.LOCAL_GPS -> reduceLocal(input, route)
        }
    }

    private fun reduceRemote(
        input: TrackerMapPointReductionInput,
        route: TrackerMapPointRoute,
    ): TrackerMapPointReductionResult {
        val state = input.state
        val point = input.point
        val remoteTrackerId = route.normalizedTrackerId

        val nextRemoteLastPoints = if (route.updateRemoteLastPoint) state.remoteLastPoints.toMutableMap().apply {
            this[remoteTrackerId] = point.copy(trackId = remoteTrackerId)
        } else {
            state.remoteLastPoints
        }
        val nextTrail = if (route.appendSingleTrail) {
            appendRemotePoint(state.trail, point.copy(trackId = remoteTrackerId), input.trailPointLimit)
        } else {
            state.trail
        }
        val nextAllQueueTrails = if (route.appendMultiTrail) {
            val updated = state.allQueueTrailsByTracker.toMutableMap()
            val base = updated[remoteTrackerId].orEmpty()
            updated[remoteTrackerId] = appendRemotePoint(base, point.copy(trackId = remoteTrackerId), input.trailPointLimit)
            updated
        } else {
            state.allQueueTrailsByTracker
        }

        return TrackerMapPointReductionResult(
            acceptedBySourcePolicy = true,
            shouldUpdateUiState = true,
            nextState = state.copy(
                remoteLastPoints = nextRemoteLastPoints,
                trail = nextTrail,
                allQueueTrailsByTracker = nextAllQueueTrails
            ),
        )
    }

    private fun reduceLocal(
        input: TrackerMapPointReductionInput,
        route: TrackerMapPointRoute,
    ): TrackerMapPointReductionResult {
        val state = input.state
        val point = input.point
        val overlayTrackerId = route.normalizedTrackerId
        if (overlayTrackerId.isBlank()) {
            return TrackerMapPointReductionResult(
                acceptedBySourcePolicy = true,
                shouldUpdateUiState = false,
                nextState = state,
            )
        }
        val localOverlayPoint = QueuedLocation(
            id = 0L,
            trackerId = overlayTrackerId,
            time = point.timestampMs,
            latitude = point.lat,
            longitude = point.lon,
            altitude = null,
            speed = null,
            bearing = null,
            accuracy = point.accuracyMeters,
            sat = null,
            prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS,
            dist = null
        )
        val nextTrail = if (route.appendSingleTrail) {
            appendQueuedPoint(state.trail, localOverlayPoint, input.trailPointLimit)
        } else {
            state.trail
        }
        val nextAllQueueTrails = if (route.appendMultiTrail) {
            val updated = state.allQueueTrailsByTracker.toMutableMap()
            val base = updated[overlayTrackerId].orEmpty()
            updated[overlayTrackerId] = appendQueuedPoint(base, localOverlayPoint, input.trailPointLimit)
            updated
        } else {
            state.allQueueTrailsByTracker
        }

        if (nextTrail === state.trail && nextAllQueueTrails === state.allQueueTrailsByTracker) {
            return TrackerMapPointReductionResult(
                acceptedBySourcePolicy = true,
                shouldUpdateUiState = false,
                nextState = state,
            )
        }
        return TrackerMapPointReductionResult(
            acceptedBySourcePolicy = true,
            shouldUpdateUiState = true,
            nextState = state.copy(
                trail = nextTrail,
                allQueueTrailsByTracker = nextAllQueueTrails,
            ),
        )
    }

    private fun appendQueuedPoint(
        currentTrail: List<QueuedLocation>,
        point: QueuedLocation,
        trailPointLimit: Int,
    ): List<QueuedLocation> {
        val last = currentTrail.lastOrNull()
        if (last != null) {
            val duplicate = last.time == point.time &&
                last.latitude == point.latitude &&
                last.longitude == point.longitude
            if (duplicate || point.time < last.time) return currentTrail
        }
        return (currentTrail + point).takeLast(trailPointLimit)
    }

    private fun appendRemotePoint(
        currentTrail: List<QueuedLocation>,
        point: TrackPointEvent,
        trailPointLimit: Int
    ): List<QueuedLocation> {
        val normalizedTime = TrackerMapSessionWindowPolicy.normalizeTimestampToMs(point.timestampMs) ?: point.timestampMs
        val last = currentTrail.lastOrNull()
        if (last != null) {
            val duplicate = last.time == normalizedTime &&
                last.latitude == point.lat &&
                last.longitude == point.lon
            if (duplicate || normalizedTime < last.time) return currentTrail
        }
        val trackerId = point.trackId.trim()
        if (trackerId.isEmpty()) return currentTrail
        val queued = QueuedLocation(
            id = 0L,
            trackerId = trackerId,
            time = normalizedTime,
            latitude = point.lat,
            longitude = point.lon,
            altitude = null,
            speed = null,
            bearing = null,
            accuracy = point.accuracyMeters,
            sat = null,
            prov = TrackerMapPointProvenancePolicy.PROVENANCE_REMOTE_STREAM,
            dist = null
        )
        return (currentTrail + queued).takeLast(trailPointLimit)
    }

}
