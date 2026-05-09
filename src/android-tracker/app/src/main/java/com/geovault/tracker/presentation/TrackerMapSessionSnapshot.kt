package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.services.TrackingRuntimeSnapshot

data class TrackerTrackModel(
    val trackerId: String,
    /**
     * Time-sorted render trail. This is the canonical, chronologically-ordered list of points
     * for both line rendering and marker placement. Do NOT reorder by provenance — the marker
     * reads `renderTrail.lastOrNull()` and lines connect points in this order.
     */
    val renderTrail: List<QueuedLocation> = emptyList(),
    val remoteHead: TrackPointEvent? = null,
) {
    /** Derived view: non-live-overlay points (server geometry, persisted DB fixes, etc.). */
    val historicalTrail: List<QueuedLocation>
        get() = renderTrail.filterNot(TrackerMapPointProvenancePolicy::isLiveOverlay)

    /** Derived view: in-memory live overlay points (bus-reduced local GPS / remote stream). */
    val liveTrail: List<QueuedLocation>
        get() = renderTrail.filter(TrackerMapPointProvenancePolicy::isLiveOverlay)
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
    val fallbackAccuracyMeters: Float? = null,
    val allowAccuracyFallback: Boolean = false,
    val fallbackAccuracyByTrackerId: Map<String, Float> = emptyMap(),
    val allowAccuracyFallbackByTrackerId: Set<String> = emptySet(),
)
