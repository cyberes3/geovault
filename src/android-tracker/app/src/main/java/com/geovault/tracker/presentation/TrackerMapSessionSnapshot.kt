package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.services.TrackingRuntimeSnapshot

data class TrackerTrackModel(
    val trackerId: String,
    val historicalTrail: List<QueuedLocation> = emptyList(),
    val liveTrail: List<QueuedLocation> = emptyList(),
    val remoteHead: TrackPointEvent? = null,
) {
    val renderTrail: List<QueuedLocation>
        get() = historicalTrail + liveTrail
}

data class TrackerMapSessionSnapshot(
    val uiState: TrackerMapUiState,
    val plan: TrackerMapStreamingPlan,
    val runtime: TrackingRuntimeSnapshot,
    val singleTrail: List<QueuedLocation>,
    val tracks: Map<String, TrackerTrackModel>,
    val acceptedRemoteLastPoints: Map<String, TrackPointEvent>,
) {
    val mode: TrackerMapDisplayMode
        get() = plan.mode

    val renderTrailsByTracker: Map<String, List<QueuedLocation>>
        get() = tracks.mapValues { it.value.renderTrail }
}

data class TrackerMapRenderCosmetics(
    val trackerColorById: Map<String, String> = emptyMap(),
    val trackerDisplayNameById: Map<String, String> = emptyMap(),
    val selectedMapTrackerId: String? = null,
    val trackerRenderOrder: List<String> = emptyList(),
    val defaultIconColorHex: String = TrackerMapIconIds.DEFAULT_COLOR_HEX,
)

data class TrackerMapAccuracyRenderModel(
    val streamedAccuracyMeters: Float? = null,
    val fallbackAccuracyMeters: Float? = null,
    val allowAccuracyFallback: Boolean = false,
    val streamedAccuracyByTrackerId: Map<String, Float> = emptyMap(),
    val fallbackAccuracyByTrackerId: Map<String, Float> = emptyMap(),
    val allowAccuracyFallbackByTrackerId: Set<String> = emptySet(),
)
