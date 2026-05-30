package com.geovault.tracker.presentation

import com.geovault.tracker.services.TrackingRuntimeSnapshot

data class TrackerMapSessionWindowState(
    val recentDataWindowByTracker: Map<String, String?> = emptyMap(),
    val currentSessionStartByTracker: Map<String, Long> = emptyMap(),
) {
    fun contextFor(trackerId: String, nowMs: Long): TrackerSessionWindowContext {
        val normalizedId = trackerId.trim()
        return TrackerSessionWindowContext(
            windowKey = recentDataWindowByTracker[normalizedId],
            nowMs = nowMs,
            currentSessionStartMs = currentSessionStartByTracker[normalizedId],
        )
    }
}

object TrackerMapSessionWindowResolver {
    fun resolve(
        recentDataWindowByTracker: Map<String, String?>,
        runtime: TrackingRuntimeSnapshot,
    ): TrackerMapSessionWindowState {
        return TrackerMapSessionWindowState(
            recentDataWindowByTracker = recentDataWindowByTracker.mapKeys { it.key.trim() }
                .filterKeys { it.isNotEmpty() },
            currentSessionStartByTracker = currentSessionStartByTracker(runtime),
        )
    }

    fun currentSessionStartByTracker(runtime: TrackingRuntimeSnapshot): Map<String, Long> {
        if (!runtime.localRecordingActive) return emptyMap()
        val sessionStart = runtime.sessionStartTimeMs
        if (sessionStart <= 0L) return emptyMap()
        val trackerId = runtime.locallyRecordedTrackerId.trim()
        if (trackerId.isEmpty()) return emptyMap()
        return mapOf(trackerId to sessionStart)
    }
}
