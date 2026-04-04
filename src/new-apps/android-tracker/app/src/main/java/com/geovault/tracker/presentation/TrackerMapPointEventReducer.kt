package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointSource

data class TrackerMapPointReductionInput(
    val state: TrackerMapUiState,
    val point: TrackPointEvent,
    val recentDataWindow: String?,
    val currentSessionStartMs: Long?,
    val pendingReopenTrackerId: String?,
    val sessionAnchorTrackerId: String?,
    val sessionAnchorUntilElapsedMs: Long,
    val nowElapsedMs: Long,
    val trailPointLimit: Int,
)

data class TrackerMapPointReductionResult(
    val acceptedBySourcePolicy: Boolean,
    val shouldUpdateUiState: Boolean,
    val nextState: TrackerMapUiState,
    val nextSessionStartMs: Long?,
    val nextSessionAnchorTrackerId: String?,
    val nextSessionAnchorUntilElapsedMs: Long,
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
                nextSessionStartMs = input.currentSessionStartMs,
                nextSessionAnchorTrackerId = input.sessionAnchorTrackerId,
                nextSessionAnchorUntilElapsedMs = input.sessionAnchorUntilElapsedMs,
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
        val pendingAnchor = isSessionAnchorResyncPending(
            trackerId = point.trackId,
            sessionAnchorTrackerId = input.sessionAnchorTrackerId,
            sessionAnchorUntilElapsedMs = input.sessionAnchorUntilElapsedMs,
            nowElapsedMs = input.nowElapsedMs
        )
        val decision = if (state.mode == TrackerMapDisplayMode.SINGLE_SESSION) {
            if (pendingAnchor.isPending) {
                TrackerMapSessionWindowDecision(
                    shouldResetTrackGeometry = false,
                    shouldIgnorePoint = false,
                    nextSessionStartMs = input.currentSessionStartMs
                )
            } else {
                TrackerMapSessionWindowPolicy.decide(
                    recentDataWindow = input.recentDataWindow,
                    currentSessionStartMs = input.currentSessionStartMs,
                    incomingPropsJson = point.propsJson,
                    allowResetOnNewSession = TrackerMapViewModel.resolveAllowSessionReset(
                        pendingReopenTrackerId = input.pendingReopenTrackerId,
                        eventTrackId = point.trackId
                    )
                )
            }
        } else {
            TrackerMapSessionWindowDecision(
                shouldResetTrackGeometry = false,
                shouldIgnorePoint = false,
                nextSessionStartMs = input.currentSessionStartMs
            )
        }

        if (decision.shouldIgnorePoint) {
            return TrackerMapPointReductionResult(
                acceptedBySourcePolicy = true,
                shouldUpdateUiState = false,
                nextState = state,
                nextSessionStartMs = decision.nextSessionStartMs,
                nextSessionAnchorTrackerId = pendingAnchor.nextTrackerId,
                nextSessionAnchorUntilElapsedMs = pendingAnchor.nextUntilElapsedMs,
            )
        }

        val nextRemoteLastPoints = state.remoteLastPoints.toMutableMap().apply {
            if (decision.shouldResetTrackGeometry) remove(point.trackId)
            this[point.trackId] = point
        }
        val nextTrail = if (
            state.mode == TrackerMapDisplayMode.SINGLE_SESSION &&
            !state.runtime.isRunning &&
            displayedTrackerId.isNotBlank() &&
            displayedTrackerId == point.trackId
        ) {
            val baseTrail = if (decision.shouldResetTrackGeometry) emptyList() else state.trail
            appendRemotePoint(baseTrail, point, input.trailPointLimit)
        } else {
            state.trail
        }
        val nextAllQueueTrails = if (
            state.mode == TrackerMapDisplayMode.ALL_QUEUE ||
            state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER
        ) {
            val updated = state.allQueueTrailsByTracker.toMutableMap()
            val base = if (decision.shouldResetTrackGeometry) {
                emptyList()
            } else {
                updated[point.trackId].orEmpty()
            }
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
            nextSessionStartMs = decision.nextSessionStartMs,
            nextSessionAnchorTrackerId = pendingAnchor.nextTrackerId,
            nextSessionAnchorUntilElapsedMs = pendingAnchor.nextUntilElapsedMs,
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
                nextSessionStartMs = input.currentSessionStartMs,
                nextSessionAnchorTrackerId = input.sessionAnchorTrackerId,
                nextSessionAnchorUntilElapsedMs = input.sessionAnchorUntilElapsedMs,
            )
        }
        val nextTrail = (trail + localOverlayPoint).takeLast(input.trailPointLimit)
        return TrackerMapPointReductionResult(
            acceptedBySourcePolicy = true,
            shouldUpdateUiState = true,
            nextState = state.copy(trail = nextTrail),
            nextSessionStartMs = input.currentSessionStartMs,
            nextSessionAnchorTrackerId = input.sessionAnchorTrackerId,
            nextSessionAnchorUntilElapsedMs = input.sessionAnchorUntilElapsedMs,
        )
    }

    private data class SessionAnchorPendingResult(
        val isPending: Boolean,
        val nextTrackerId: String?,
        val nextUntilElapsedMs: Long,
    )

    private fun isSessionAnchorResyncPending(
        trackerId: String,
        sessionAnchorTrackerId: String?,
        sessionAnchorUntilElapsedMs: Long,
        nowElapsedMs: Long
    ): SessionAnchorPendingResult {
        if (nowElapsedMs >= sessionAnchorUntilElapsedMs) {
            return SessionAnchorPendingResult(
                isPending = false,
                nextTrackerId = null,
                nextUntilElapsedMs = 0L
            )
        }
        val isPending = sessionAnchorTrackerId == trackerId.trim()
        return SessionAnchorPendingResult(
            isPending = isPending,
            nextTrackerId = sessionAnchorTrackerId,
            nextUntilElapsedMs = sessionAnchorUntilElapsedMs
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
