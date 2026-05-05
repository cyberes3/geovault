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
        val displayedTrackerId = TrackerMapDisplayIds.effectiveDisplayedTrackerId(state)
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
            TrackPointSource.LOCAL_GPS -> reduceLocal(input, displayedTrackerId)
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

    private fun reduceLocal(
        input: TrackerMapPointReductionInput,
        displayedTrackerId: String,
    ): TrackerMapPointReductionResult {
        val state = input.state
        val point = input.point
        val overlayTrackerId = point.trackId.trim()
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
            prov = "local_gps",
            dist = null
        )
        val nextTrail = if (shouldUpdateSingleTrail(state, displayedTrackerId, overlayTrackerId)) {
            appendQueuedPoint(state.trail, localOverlayPoint, input.trailPointLimit)
        } else {
            state.trail
        }
        val nextAllQueueTrails = if (
            state.mode == TrackerMapDisplayMode.ALL_QUEUE ||
            state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER
        ) {
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

    private fun shouldUpdateSingleTrail(
        state: TrackerMapUiState,
        displayedTrackerId: String,
        localTrackerId: String,
    ): Boolean {
        if (state.mode != TrackerMapDisplayMode.SINGLE_SESSION) return false
        val selectedTrackerId = state.runtime.selectedTrackerId.trim()
        return displayedTrackerId.isBlank() ||
            selectedTrackerId.isBlank() ||
            displayedTrackerId == localTrackerId
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
            prov = "remote_stream",
            dist = null
        )
        return (currentTrail + queued).takeLast(trailPointLimit)
    }

}
