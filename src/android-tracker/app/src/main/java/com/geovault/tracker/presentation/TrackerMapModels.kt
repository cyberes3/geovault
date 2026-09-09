package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.domain.TrackPoint
import com.geovault.tracker.positioning.RecordingRuntime
import com.geovault.tracker.positioning.TrackingRuntimeSnapshot
import org.maplibre.android.geometry.LatLngBounds

import com.geovault.tracker.map.MapSessionEngine
import com.geovault.tracker.map.MapRenderMath
import com.geovault.tracker.map.TrailView
enum class TrackerMapDisplayMode {
    SINGLE_SESSION,
    ALL_QUEUE,
    GROUP_PLACEHOLDER,
}

enum class TrackerMapFitTrailMode {
    Animated,
    Instant,
}

internal data class TrackerMapUiState(
    val runtime: TrackingRuntimeSnapshot = TrackingRuntimeSnapshot(),
    val activeStreamedTrackerIds: Set<String> = emptySet(),
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
    /**
     * Set by [com.geovault.tracker.map.MapSessionEngine.applyRosterRemoval] the moment the tracker that was displayed drops
     * out of the roster (deleted, unshared, or an accepted share revoked server-side). Cleared
     * automatically the next time `displayedTrackerId` is set to something new. Surfaces a
     * "no longer available" status in place of the stale name instead of leaving the map
     * frozen on the last-known marker/trail while `streamingStatus` still nominally reads Live.
     */
    val unavailableTrackerNotice: TrackerMapUnavailableNotice? = null,
    /**
     * Set by [com.geovault.tracker.map.MapSessionEngine]'s heartbeat collector when a wanted
     * subscription has been unhealthy for an extended period despite a usable network being
     * present -- a strong signal the OEM is background-killing the streaming connection.
     * Surfaces a dismissible, actionable hint on the map instead of the failure only being
     * visible in capture logs.
     */
    val batteryOptimizationHintVisible: Boolean = false,
)

data class TrackerMapUnavailableNotice(
    val trackerId: String,
    val trackerName: String,
)

data class TrackerMapRenderPackage(
    val renderState: com.geovault.common.maps.render.MapRenderState = com.geovault.common.maps.render.MapRenderState(),
    val bounds: LatLngBounds? = null,
    val selectionLockPoint: Pair<Double, Double>? = null,
    val liveHead: Pair<Double, Double>? = null,
    val revision: Long = 0L,
)

data class TrackerMapRecordingChrome(
    val selectedTrackerId: String = "",
    val locallyRecordedTrackerId: String = "",
    val localRecordingActive: Boolean = false,
    val lastTrackedLatitude: Double? = null,
    val lastTrackedLongitude: Double? = null,
    val lastTrackedTimestampMs: Long = 0L,
    val lastAccuracyMeters: Float? = null,
)

data class TrackerMapChromeModel(
    val chip: TrackerMapTopLeftChipUiModel = TrackerMapTopLeftChipUiModel.Hidden,
    val lockFab: TrackerMapLockFabBehavior = TrackerMapLockFabBehavior.FollowLock(isEnabled = false),
    val showMyLocationFab: Boolean = false,
    val liveActiveFit: LiveActiveFitVisibility = LiveActiveFitVisibility(
        showButton = false,
        buttonEnabled = false,
    ),
    val liveActiveFitEnabled: Boolean = false,
    val userLocation: TrackerMapUserLocationDecision = TrackerMapUserLocationDecision(
        shouldStreamGps = false,
        shouldEnablePuck = false,
        blockers = emptySet(),
    ),
    val gpsAccuracy: TrackerMapGpsAccuracyIndicatorUiModel = TrackerMapGpsAccuracyIndicatorUiModel(),
    val keepScreenOnWhileViewingMap: Boolean = false,
    val recording: TrackerMapRecordingChrome = TrackerMapRecordingChrome(),
    val streamingStatus: TrackerMapStreamingStatusUiModel = TrackerMapStreamingStatusUiModel(),
    val trailDegradedVisible: Boolean = false,
)

data class TrackerMapSelectionCard(
    val trackerId: String,
    val trackerName: String,
    val latitude: Double,
    val longitude: Double,
    /**
     * "Last reported at" timestamp from [com.geovault.tracker.map.MapRenderMath.resolveLastReportedAtMs]
     * in `buildSelectionCard`. For the device's own actively-recording tracker this is
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

/**
 * Clears all three map locks unconditionally -- the right default whenever a *new* camera
 * context is being established (a fresh manual lock target, a context reset, a roster removal,
 * auto-lock on recording start, etc.). The one exception is enabling live active fit in
 * SINGLE_SESSION, which composes with an existing selection lock instead of clearing it; see
 * [MapRenderMath.composesWithSelectionLock] and
 * [com.geovault.tracker.map.MapSessionEngine.setLiveActiveFit].
 */
internal fun TrackerMapUiState.withAllMapLocksDisabled(): TrackerMapUiState = copy(
    followLockEnabled = false,
    liveActiveFitEnabled = false,
    selectionLockTrackerId = "",
)

/**
 * True when any of the three map locks currently claims the camera. `followLockEnabled` and
 * `liveActiveFitEnabled` are always mutually exclusive with each other, and both are mutually
 * exclusive with `selectionLockTrackerId` in every mode *except* SINGLE_SESSION, where a
 * selection lock and live active fit may both be set at once (see
 * [MapRenderMath.composesWithSelectionLock]) -- this still only needs to check
 * "is at least one set" and doesn't care which combination. Used to gate
 * [com.geovault.tracker.map.MapTrailEngine] reload-fit consumption so an automatic
 * reload-landing fit never fights a lock that's already claimed the camera.
 */
internal fun TrackerMapUiState.hasAnyMapLockActive(): Boolean =
    followLockEnabled || liveActiveFitEnabled || selectionLockTrackerId.trim().isNotEmpty()

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

data class TrackerMapResolvedPoint(
    val latitude: Double,
    val longitude: Double,
    val lastUpdatedMs: Long?,
    val accuracyMeters: Float?,
)

internal data class TrackerMapEffectiveSessionInput(
    val state: TrackerMapUiState,
    val plan: TrackerMapStreamingPlan,
    val trailPointLimit: Int,
    val visibleTrackerIds: Set<String>? = null,
    val nowMs: Long = System.currentTimeMillis(),
    val singleTrail: List<QueuedLocation> = emptyList(),
    val allQueueTrailsByTracker: Map<String, List<QueuedLocation>> = emptyMap(),
    val remoteLastPoints: Map<String, TrackPoint> = emptyMap(),
)

internal data class TrackerMapEffectiveSession(
    val snapshot: TrackerMapSessionSnapshot,
    val liveHead: Pair<Double, Double>?,
)

data class TrackerMapStreamingDecisionInput(
    val mode: TrackerMapDisplayMode,
    val remoteSubscriptionIds: Set<String>,
    val displayedTrackerId: String,
    val displayedTrackerName: String,
)

sealed class TrackerMapStreamingCommand {
    data class Start(val trackerIds: Set<String>, val trackerName: String?) : TrackerMapStreamingCommand()
    data object Stop : TrackerMapStreamingCommand()
    data object NoOp : TrackerMapStreamingCommand()
}

sealed class TrackerMapAutoLockOnRecordingResult {
    data object None : TrackerMapAutoLockOnRecordingResult()
    data class SelectionLock(val trackerId: String) : TrackerMapAutoLockOnRecordingResult()
    data object LiveActiveFit : TrackerMapAutoLockOnRecordingResult()
}

internal data class TrackerRosterRemovalOutcome(
    val nextState: TrackerMapUiState,
    val changed: Boolean,
    val shouldRefreshStreamTargets: Boolean,
    val nextTrails: TrailView = TrailView(),
)

internal data class TrackerMapContextReset(
    val nextState: TrackerMapUiState,
    val nextTrails: TrailView = TrailView(),
)

data class TrailReloadGuardInput(
    val mode: TrackerMapDisplayMode,
    val trailSize: Int,
    val runtimeRunning: Boolean,
    val displayedTrackerId: String,
    val trailReloadPlan: TrackerMapTrailReloadPlan,
)

data class TrackerMapLockFabInput(
    val mode: TrackerMapDisplayMode,
    val displayedTrackerId: String,
    val selectionLockTrackerId: String,
    val liveActiveFitEnabled: Boolean,
    val followLockEnabled: Boolean,
)

sealed class TrackerMapLockFabBehavior {
    data class SelectionLock(
        val displayedTrackerId: String,
        val isLocked: Boolean,
    ) : TrackerMapLockFabBehavior()

    data class LiveActiveFit(val isEnabled: Boolean) : TrackerMapLockFabBehavior()
    data class FollowLock(val isEnabled: Boolean) : TrackerMapLockFabBehavior()
}

data class TrackerMapGpsAccuracyIndicatorUiModel(
    val isVisible: Boolean = false,
)

data class TrackerMapGroupModeOption(
    val groupId: String,
    val groupName: String,
    val trackerIds: Set<String>,
)

data class TrackerMapGroupModeSelection(
    val groupId: String?,
    val trackerIds: Set<String>,
)

data class TrackerMapStreamSeedInput(
    val mode: TrackerMapDisplayMode,
    val runtimeRunning: Boolean,
    val selectedTrackerId: String,
    val displayedTrackerId: String,
    val rosterTrackerIds: Collection<String>,
    val groupSelection: TrackerMapGroupModeSelection,
)

data class TrackerMapTrailSeedInput(
    val mode: TrackerMapDisplayMode,
    val runtimeRunning: Boolean,
    val activeTrackerId: String,
    val rosterTrackerIds: Collection<String>,
    val groupSelection: TrackerMapGroupModeSelection,
    val renderMetadataSignature: String = "",
)

enum class TrackerMapViewContext {
    SINGLE_TRACKER,
    GROUP,
}

data class TrackerMapResumeInput(
    val trackingRunning: Boolean,
    val mapReady: Boolean,
    val showAllTrackers: Boolean,
    val mapViewContext: TrackerMapViewContext,
    val activeStreamedTrackerIds: Set<String>,
    val currentGroupTrackIds: Set<String>,
    val selectedTrackerId: String,
    val displayedTrackerId: String,
    val hasTrailPoints: Boolean,
    val hasPendingInitialTracker: Boolean = false,
    val backgroundedDurationMs: Long,
)

sealed class TrackerMapResumeDecision {
    data object NoOp : TrackerMapResumeDecision()
    data object MultiContextNoStreaming : TrackerMapResumeDecision()
    data class StartMultiContextStreaming(val trackerIds: Set<String>) : TrackerMapResumeDecision()
    data object ClearSingleTrackerState : TrackerMapResumeDecision()
    data class LoadSingleTrackerRuntime(val trackerId: String) : TrackerMapResumeDecision()
    data class LoadSingleTrackerBootstrap(val trackerId: String) : TrackerMapResumeDecision()
    data object RestartDisplayedTrackerStreaming : TrackerMapResumeDecision()
}

enum class TrackerMapRuntimeInvariant {
    TRACKING_REQUIRES_SELECTED_TRACKER,
    TRACKING_WITH_POINTS_MUST_NOT_FORCE_DESTRUCTIVE_RELOAD,
    SINGLE_LOAD_COMMANDS_MUST_BE_IDEMPOTENT,
}

data class TrackerMapRuntimeInvariantStatus(
    val invariant: TrackerMapRuntimeInvariant,
    val satisfied: Boolean,
    val details: String,
)

data class TrackerMapReopenOutcome(
    val decision: TrackerMapResumeDecision,
    val invariants: List<TrackerMapRuntimeInvariantStatus>,
)

enum class TrackerMapStreamingStatus {
    INACTIVE,
    CONNECTING,
    LIVE,
    RECONNECTING,
    FAILED,
}

data class TrackerMapStreamingStatusUiModel(
    val status: TrackerMapStreamingStatus = TrackerMapStreamingStatus.INACTIVE,
    val activeCount: Int = 0,
    val failureReason: String? = null,
)

data class LiveActiveFitInput(
    val mode: TrackerMapDisplayMode,
    val followLockArmed: Boolean,
    val liveActiveFitEnabled: Boolean,
    val hasTrailPoints: Boolean,
    val isSelectedDefaultTracker: Boolean,
    val hasMultipleTrackersOnMap: Boolean,
)

data class LiveActiveFitVisibility(
    val showButton: Boolean,
    val buttonEnabled: Boolean,
)

data class TrackerMapUserLocationInput(
    val isMapActive: Boolean,
    val hasLocationPermission: Boolean,
    val isMapReady: Boolean,
    val userLocationRequestedThisSession: Boolean,
    val displayedTrackerId: String = "",
    val locallyRecordedTrackerId: String = "",
)

enum class TrackerMapUserLocationBlocker {
    MapInactive,
    MissingPermission,
    MapNotReady,
    LocationNotRequestedThisSession,
    OwnRecordedTrackerOnScreen,
}

data class TrackerMapUserLocationDecision(
    val shouldStreamGps: Boolean,
    val shouldEnablePuck: Boolean,
    val blockers: Set<TrackerMapUserLocationBlocker>,
)

internal enum class TrackerMapRuntimeTransition {
    STARTED,
    STOPPED,
    NONE,
}

internal data class TrackerMapRuntimeResyncDecision(
    val transition: TrackerMapRuntimeTransition,
    val restartTrackPointStream: Boolean,
    val restartDisplayedStreaming: Boolean,
)

internal data class TrackerMapSessionIntent(
    val mode: TrackerMapDisplayMode,
    val runtime: TrackingRuntimeSnapshot,
    val selectedTrackerId: String = "",
    val selectedTrackerName: String = "",
    val displayedTrackerId: String,
    val displayedTrackerName: String,
    val rosterTrackerIds: Set<String>,
    val groupSelection: TrackerMapGroupModeSelection,
    val activeStreamedTrackerIds: Set<String>,
)

data class TrackerMapStreamingPlan(
    val mode: TrackerMapDisplayMode,
    val selectedTrackerId: String,
    val displayedTrackerId: String,
    val displayedTrackerName: String,
    val resolvedGroupId: String,
    val groupTrackerIds: Set<String>,
    val visibleRosterTrackerIds: Set<String>,
    val locallyRecordedTrackerIds: Set<String>,
    val remoteSubscriptionIds: Set<String>,
    val acceptedRemoteTrackerIds: Set<String>,
    val localOverlayTrackerIds: Set<String>,
    val trailReloadPlan: TrackerMapTrailReloadPlan,
)

internal data class TrackerMapSessionBuildInput(
    val state: TrackerMapUiState,
    val plan: TrackerMapStreamingPlan,
    val singleTrail: List<QueuedLocation> = emptyList(),
    val localRuntimeOverlayTrails: Map<String, List<QueuedLocation>> = emptyMap(),
    val remoteLastPoints: Map<String, TrackPoint> = emptyMap(),
    val visibleTrackerIds: Set<String>? = null,
    val nowMs: Long = System.currentTimeMillis(),
)

internal data class TrackerMapSessionPointInput(
    val snapshot: TrackerMapSessionSnapshot,
    val point: TrackPoint,
    val trailPointLimit: Int,
    val visibleTrackerIds: Set<String>? = null,
    val nowMs: Long = System.currentTimeMillis(),
)

internal data class TrackerMapSessionPointResult(
    val acceptedBySourcePolicy: Boolean,
    val shouldUpdate: Boolean,
    val nextSnapshot: TrackerMapSessionSnapshot,
)

internal data class TrackerMapPointReductionInput(
    val state: TrackerMapUiState,
    val trails: TrailView = TrailView(),
    val point: TrackPoint,
    val trailPointLimit: Int,
    val sessionPlan: TrackerMapStreamingPlan,
)

internal data class TrackerMapPointReductionResult(
    val acceptedBySourcePolicy: Boolean,
    val shouldUpdateUiState: Boolean,
    val nextState: TrackerMapUiState,
    val nextTrails: TrailView = TrailView(),
)

enum class TrackerMapTrailSource {
    SINGLE_SERVER,
    MULTI_SERVER,
    SINGLE_QUEUE,
}

data class TrackerMapTrailReloadInput(
    val mode: TrackerMapDisplayMode,
    val runtimeRunning: Boolean,
    val selectedTrackerId: String,
    val locallyRecordedTrackerId: String = "",
    val activeTrackerId: String,
    val rosterTrackerIds: Set<String>,
    val groupSelection: TrackerMapGroupModeSelection,
)

data class TrackerMapTrailReloadPlan(
    val source: TrackerMapTrailSource,
    val singleTrackerId: String = "",
    val trackerIds: Set<String> = emptySet(),
    val overlayTrackerId: String? = null,
    val activeTrackerId: String = "",
    val resolvedGroupId: String = "",
)

data class TrackerMapTrailLoadResult(
    val serverTrails: Map<String, List<QueuedLocation>>,
    val queueOverlaysByTracker: Map<String, List<QueuedLocation>>,
    val singleTrailSeed: List<QueuedLocation>,
    val authoritativeServerTrackerIds: Set<String> = emptySet(),
) {
    companion object {
        val EMPTY: TrackerMapTrailLoadResult = TrackerMapTrailLoadResult(
            serverTrails = emptyMap(),
            queueOverlaysByTracker = emptyMap(),
            singleTrailSeed = emptyList(),
            authoritativeServerTrackerIds = emptySet(),
        )
    }
}
