package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.services.RecordingRuntime
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import org.maplibre.android.geometry.LatLngBounds

enum class TrackerMapFitTrailMode {
    Animated,
    Instant,
}

data class TrackerMapUiState(
    val runtime: TrackingRuntimeSnapshot = TrackingRuntimeSnapshot(),
    val trail: List<QueuedLocation> = emptyList(),
    val allQueueTrailsByTracker: Map<String, List<QueuedLocation>> = emptyMap(),
    val remoteLastPoints: Map<String, TrackPointEvent> = emptyMap(),
    val activeStreamedTrackerIds: Set<String> = emptySet(),
    val streamTargetIds: Set<String> = emptySet(),
    val streamingStatus: TrackerMapStreamingStatusUiModel = TrackerMapStreamingStatusUiModel(),
    val currentGroupId: String = "",
    val groupModeOptions: List<TrackerMapGroupModeOption> = emptyList(),
    val displayedTrackerId: String = "",
    val displayedTrackerName: String = "",
    val isBottomCardVisible: Boolean = false,
    val selectedMapTracker: TrackerMapSelectionCard? = null,
    val selectionLockTrackerId: String = "",
    val mode: TrackerMapDisplayMode = TrackerMapDisplayMode.SINGLE_SESSION,
    val followLockEnabled: Boolean = false,
    val liveActiveFitEnabled: Boolean = false,
    val isGeometryLoading: Boolean = false,
    val renderMetadataSignature: String = "",
)

data class TrackerMapRenderPackage(
    val renderState: com.geovault.common.maps.render.MapRenderState = com.geovault.common.maps.render.MapRenderState(),
    val bounds: LatLngBounds? = null,
    val selectionLockPoint: Pair<Double, Double>? = null,
    val revision: Long = 0L,
)

data class TrackerMapSelectionCard(
    val trackerId: String,
    val trackerName: String,
    val latitude: Double,
    val longitude: Double,
    /**
     * "Last reported at" timestamp routed through [TrackerLastReportedAtPolicy] in
     * `buildSelectionCard`. For the device's own actively-recording tracker this is
     * `TrackingRuntimeSnapshot.lastPointSentAtMs` (last successful upload); for every
     * other tracker it is the resolver's freshest data-point timestamp. This is the
     * correct input for both the map info box "Updated ... ago" text and
     * [com.geovault.tracker.policy.ActiveButDeadTrackerPolicy.isActiveButDead] stale
     * coloring. `null` means "no reported timestamp known yet" -- renderers should show
     * "Waiting for data" rather than fabricate a value.
     */
    val lastUpdatedMs: Long?,
    val accuracyMeters: Float?,
    val isOwned: Boolean,
    val serverMetadataUpdatedAtMs: Long? = null,
    val lastPointParamsMs: Long? = null,
)

/**
 * HISTORY-FETCH POLICY: server-side trail/geometry history is fetched ONCE per logical context,
 * never on lifecycle ticks like resume-from-background, runtime state changes, or live cosmetic
 * metadata refreshes. The four "load" reasons below are the only paths that may hit the server;
 * everything else is a render-only refresh that consumes the WS data already in memory.
 *
 *  - [GenericMapRefresh]: re-render only. Fired by runtime state ticks / WS-driven UI churn.
 *  - [MetadataMapRefresh]: re-render only. Fired by tracker name/color/visibility/group structure
 *    changes. WS provides the live data; we never re-fetch geometry just because a tracker's
 *    `updated_at` advanced or its cached geometry grew. This was historically wired to a
 *    multi-server reload, which caused the entire group's history to re-download every time a
 *    single live point arrived.
 *  - The remaining four reasons are explicit context-change triggers that legitimately need a
 *    one-shot server fetch (entering a new mode/group, loading a tracker for the first time,
 *    streaming starting, or returning to the selected tracker after group streaming ends).
 */
internal enum class TrackerMapTrailReloadReason(
    val allowServerHistoryFetch: Boolean,
    val allowMultiServerHistoryFetch: Boolean = allowServerHistoryFetch,
) {
    GenericMapRefresh(allowServerHistoryFetch = false, allowMultiServerHistoryFetch = false),
    MetadataMapRefresh(allowServerHistoryFetch = false, allowMultiServerHistoryFetch = false),
    MapContextChange(allowServerHistoryFetch = true),
    ExplicitTrackerLoad(allowServerHistoryFetch = true),
    StreamingStart(allowServerHistoryFetch = true),
    RestoreSelectedAfterStreaming(allowServerHistoryFetch = true),
    RecentDataWindowChanged(allowServerHistoryFetch = true),
    HistoryCleared(allowServerHistoryFetch = true),
    RosterChanged(allowServerHistoryFetch = true),
    ;

    /**
     * Strength score used to merge coalesced reload requests:
     *   2 = forces a server history fetch (single + multi)
     *   1 = forces a multi-server fetch only
     *   0 = render-only refresh
     */
    fun strength(): Int = when {
        allowServerHistoryFetch -> 2
        allowMultiServerHistoryFetch -> 1
        else -> 0
    }
}

/**
 * Returns the stronger of the receiver and [incoming] under [TrackerMapTrailReloadReason.strength].
 * `null` is treated as "nothing pending"; any non-null incoming wins. When two non-null reasons
 * tie on strength, the receiver wins so a previously-recorded request is not displaced by a
 * later equivalent one (stable / minimum churn). Used by the reload coalescing loop.
 */
internal fun TrackerMapTrailReloadReason?.mergedWith(
    incoming: TrackerMapTrailReloadReason,
): TrackerMapTrailReloadReason {
    val current = this ?: return incoming
    return if (incoming.strength() > current.strength()) incoming else current
}

internal fun TrackerMapUiState.withAllMapLocksDisabled(): TrackerMapUiState = copy(
    followLockEnabled = false,
    liveActiveFitEnabled = false,
    selectionLockTrackerId = "",
)

/**
 * Hides the per-tracker info card and drops the in-card selection. Intentionally does NOT clear
 * `selectionLockTrackerId` — the camera lock is tied to a tracker, not to the card's visibility.
 * Closing the card via background tap, marker re-tap, or any other "dismiss the card" gesture
 * leaves the camera locked to whichever tracker the user previously locked. Context resets that
 * legitimately need to drop the lock chain `withAllMapLocksDisabled()` explicitly.
 */
internal fun TrackerMapUiState.withClearedMapSelectionCard(): TrackerMapUiState = copy(
    isBottomCardVisible = false,
    selectedMapTracker = null,
)
