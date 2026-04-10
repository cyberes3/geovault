package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointSource

data class TrackerMapPointReductionInput(
    val state: TrackerMapUiState,
    val point: TrackPointEvent,
    val trailPointLimit: Int,
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
        val displayedTrackerId = effectiveDisplayedTrackerId(state)
        val accepted = TrackerMapPointAcceptancePolicy.shouldAccept(
            event = point,
            input = TrackerMapPointAcceptanceInput(
                trackingRunning = state.runtime.isRunning,
                mode = state.mode,
                displayedTrackerId = displayedTrackerId,
                selectedTrackerId = state.runtime.selectedTrackerId,
                activeStreamedTrackerIds = state.activeStreamedTrackerIds
            )
        )
        if (!accepted) {
            return TrackerMapPointReductionResult(
                acceptedBySourcePolicy = false,
                shouldUpdateUiState = false,
                nextState = state,
            )
        }

        return when (point.source) {
            TrackPointSource.REMOTE_STREAM -> reduceRemote(input, displayedTrackerId)
            TrackPointSource.LOCAL_GPS -> reduceLocal(input)
        }
    }

    private fun reduceRemote(
        input: TrackerMapPointReductionInput,
        displayedTrackerId: String
    ): TrackerMapPointReductionResult {
        val state = input.state
        val point = input.point

        val nextRemoteLastPoints = state.remoteLastPoints.toMutableMap().apply {
            this[point.trackId] = point
        }
        val nextTrail = if (
            state.mode == TrackerMapDisplayMode.SINGLE_SESSION &&
            !state.runtime.isRunning &&
            displayedTrackerId.isNotBlank() &&
            displayedTrackerId == point.trackId
        ) {
            appendRemotePoint(state.trail, point, input.trailPointLimit)
        } else {
            state.trail
        }
        val nextAllQueueTrails = if (
            state.mode == TrackerMapDisplayMode.ALL_QUEUE ||
            state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER
        ) {
            val updated = state.allQueueTrailsByTracker.toMutableMap()
            val base = updated[point.trackId].orEmpty()
            updated[point.trackId] = appendRemotePoint(base, point, input.trailPointLimit)
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

    private fun reduceLocal(input: TrackerMapPointReductionInput): TrackerMapPointReductionResult {
        val state = input.state
        val point = input.point
        val localOverlayPoint = QueuedLocation(
            id = 0L,
            time = point.timestampMs,
            latitude = point.lat,
            longitude = point.lon,
            altitude = null,
            speed = null,
            bearing = null,
            accuracy = point.accuracyMeters,
            sat = null,
            prov = "local_gps",
            dist = null
        )
        val trail = state.trail
        val last = trail.lastOrNull()
        val isDuplicateTail = last != null &&
            last.time == localOverlayPoint.time &&
            last.latitude == localOverlayPoint.latitude &&
            last.longitude == localOverlayPoint.longitude
        if (isDuplicateTail) {
            return TrackerMapPointReductionResult(
                acceptedBySourcePolicy = true,
                shouldUpdateUiState = false,
                nextState = state,
            )
        }
        val nextTrail = (trail + localOverlayPoint).takeLast(input.trailPointLimit)
        return TrackerMapPointReductionResult(
            acceptedBySourcePolicy = true,
            shouldUpdateUiState = true,
            nextState = state.copy(trail = nextTrail),
        )
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
        val queued = QueuedLocation(
            id = 0L,
            time = normalizedTime,
            latitude = point.lat,
            longitude = point.lon,
            altitude = null,
            speed = null,
            bearing = null,
            accuracy = point.accuracyMeters,
            sat = null,
            prov = "remote_stream",
            dist = null
        )
        return (currentTrail + queued).takeLast(trailPointLimit)
    }

    private fun effectiveDisplayedTrackerId(state: TrackerMapUiState): String {
        return state.displayedTrackerId.trim().ifBlank { state.runtime.selectedTrackerId.trim() }
    }
}
