package com.geovault.tracker.history

import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.Tracker
import com.geovault.tracker.services.TrackingRuntimeSnapshot

/**
 * Owns recording start/stop semantics for map history: recompose on session start,
 * clear display boundary on stop, and defer recompose until session start is known.
 * User-initiated clears use [TrackerHistoryIntent.Clear] elsewhere.
 */
class TrackerHistorySessionBoundary {
    private var pendingRecomposeTrackerId: String? = null

    fun onRecordingStarted(
        trackerId: String,
        trackers: List<Tracker>,
        sessionStartMs: Long?,
        repository: TrackerHistoryRepository,
    ) {
        val normalized = trackerId.trim()
        if (normalized.isEmpty()) return
        val sessionStart = sessionStartMs?.takeIf { it > 0L }
        if (sessionStart != null) {
            pendingRecomposeTrackerId = null
            recomposeTrackerSnapshot(
                repository = repository,
                trackerId = normalized,
                trackers = trackers,
                activeSessionStartMs = sessionStart,
            )
        } else {
            pendingRecomposeTrackerId = normalized
        }
    }

    fun onRecordingStopped(
        trackerId: String,
        trackers: List<Tracker>,
        dispatcher: TrackerHistoryIntentDispatcher,
    ) {
        pendingRecomposeTrackerId = null
        val normalized = trackerId.trim()
        if (normalized.isEmpty()) return
        val window = TrackerHistoryWindowResolver.fromTracker(
            trackers.firstOrNull { it.id.trim() == normalized },
        )
        dispatcher.dispatch(
            TrackerHistoryIntent.Clear(
                boundary = TrackerHistoryClearBoundary(
                    trackerId = normalized,
                    clearedAtMs = System.currentTimeMillis(),
                    activeSessionStartMs = null,
                ),
                window = window,
            ),
        )
    }

    fun onRuntimeUpdated(
        runtime: TrackingRuntimeSnapshot,
        trackers: List<Tracker>,
        repository: TrackerHistoryRepository,
    ) {
        val pendingTrackerId = pendingRecomposeTrackerId ?: return
        if (!runtime.localRecordingActive) {
            pendingRecomposeTrackerId = null
            return
        }
        val activeTrackerId = runtime.locallyRecordedTrackerId.trim()
            .ifBlank { runtime.selectedTrackerId.trim() }
        if (activeTrackerId != pendingTrackerId) return
        val sessionStart = runtime.sessionStartTimeMs.takeIf { it > 0L } ?: return
        pendingRecomposeTrackerId = null
        recomposeTrackerSnapshot(
            repository = repository,
            trackerId = pendingTrackerId,
            trackers = trackers,
            activeSessionStartMs = sessionStart,
        )
        GeoVaultCaptureLog.i(
            TAG,
            "map_update history_pending_session_recompose tracker=$pendingTrackerId sessionStart=$sessionStart",
        )
    }

    private fun recomposeTrackerSnapshot(
        repository: TrackerHistoryRepository,
        trackerId: String,
        trackers: List<Tracker>,
        activeSessionStartMs: Long?,
    ) {
        val normalized = trackerId.trim()
        if (normalized.isEmpty()) return
        val window = TrackerHistoryWindowResolver.fromTracker(
            trackers.firstOrNull { it.id.trim() == normalized },
        )
        if (!window.isCurrentSession && !window.isSession) return
        val key = TrackerHistoryKey(normalized, window)
        val result = repository.composeAndPublish(
            key = key,
            activeSessionStartMs = activeSessionStartMs,
        )
        GeoVaultCaptureLog.i(
            TAG,
            "map_update history_recompose tracker=$normalized window=${window.normalizedKey} " +
                "sessionStart=${activeSessionStartMs ?: -1} committed=${result.committed} " +
                "reason=${result.reason} points=${result.snapshot.points.size}",
        )
    }

    companion object {
        private const val TAG = "TrackerHistorySessionBoundary"
    }
}
