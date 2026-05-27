package com.geovault.tracker.presentation

import android.app.Application
import android.os.SystemClock
import com.geovault.common.logging.GeoVaultCaptureLog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.tracker.MapStreamingServiceHelper
import com.geovault.tracker.SelectedTrackerManager
import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.Tracker
import com.geovault.tracker.RepositoryResult
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.tracker.db.AppDatabase
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.data.TrackerManagementRepository
import com.geovault.tracker.data.TrackerManagementStateStore
import com.geovault.tracker.settings.TrackerSettingsRepository
import com.geovault.tracker.policy.StreamingTargetPolicy
import com.geovault.tracker.policy.StreamingTargetPolicyInput
import com.geovault.tracker.policy.TrackPointBus
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.location.TrackingLifecycleState
import com.geovault.tracker.services.LiveStreamRuntimeSnapshot
import com.geovault.tracker.services.LiveStreamRuntimeStateStore
import com.geovault.tracker.services.RecordingRuntime
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import com.geovault.tracker.services.TrackingRuntimeStateStore
import com.geovault.tracker.ui.TrackerPointTimestamps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds

/**
 * Tells the map's `fitTrailEvents` consumer how to apply a bounds fit.
 *
 * - [Animated]: smooth animateCamera. Used for user-initiated fits (live-active fit
 *   toggle, incoming live track points while live-active fit is on) where the motion
 *   is feedback the user expects to see.
 * - [Instant]: snapshot moveCamera. Used for system-initiated fits (post-reload
 *   re-fit) where animation would be a visible jolt the user did not request — e.g.
 *   on first map open, when the server geometry slightly enlarges the locally-preloaded
 *   bounds and the camera was already framed correctly by the InitialFit directive.
 */
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

private fun TrackerMapUiState.withAllMapLocksDisabled(): TrackerMapUiState = copy(
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
private fun TrackerMapUiState.withClearedMapSelectionCard(): TrackerMapUiState = copy(
    isBottomCardVisible = false,
    selectedMapTracker = null,
)

class TrackerMapViewModel(application: Application) : AndroidViewModel(application) {
    internal enum class HistoryClearRefreshAction {
        REFRESH_GROUP_OR_ALL,
        REFRESH_DISPLAYED_SINGLE,
        REFRESH_SELECTED_SINGLE,
        NO_OP
    }

    companion object {
        const val TAG = "TrackerMapViewModel"
        const val TRAIL_POINT_LIMIT = 4000

        // Upper bound for the local Room queue load. Mirrors TrackingService.MAX_QUEUE_SIZE so we
        // always read every retained point and let the session-aware decimator decide which to
        // drop, rather than letting `ORDER BY time DESC LIMIT TRAIL_POINT_LIMIT` silently truncate
        // the head of the previous session below the SQL layer.
        private const val QUEUE_TRAIL_FETCH_LIMIT = 5000

        @JvmStatic
        internal fun resolveStreamTargetIds(
            mode: TrackerMapDisplayMode,
            runtimeRunning: Boolean,
            selectedTrackerId: String,
            displayedTrackerId: String,
            rosterTrackerIds: Set<String>,
            groupTrackerIds: Set<String> = emptySet(),
            groupId: String? = null,
        ): Set<String> {
            return TrackerMapSessionProjector.project(
                TrackerMapSessionIntent(
                    mode = mode,
                    runtime = TrackingRuntimeSnapshot(
                        isRunning = runtimeRunning,
                        recordingRuntime = RecordingRuntime(
                            sessionActive = runtimeRunning,
                            selectedTrackerId = selectedTrackerId,
                        ),
                        selectedTrackerId = selectedTrackerId,
                    ),
                    displayedTrackerId = displayedTrackerId,
                    displayedTrackerName = "",
                    rosterTrackerIds = rosterTrackerIds,
                    groupSelection = TrackerMapGroupModeSelection(groupId = groupId, trackerIds = groupTrackerIds),
                    activeStreamedTrackerIds = emptySet(),
                )
            ).remoteSubscriptionIds
        }

        @JvmStatic
        internal fun resolveHistoryClearRefreshAction(
            mode: TrackerMapDisplayMode,
            displayedTrackerId: String,
            selectedTrackerId: String,
            clearedTrackerId: String
        ): HistoryClearRefreshAction {
            if (mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER || mode == TrackerMapDisplayMode.ALL_QUEUE) {
                return HistoryClearRefreshAction.REFRESH_GROUP_OR_ALL
            }
            val normalizedDisplayed = displayedTrackerId.trim()
            val normalizedSelected = selectedTrackerId.trim()
            val normalizedCleared = clearedTrackerId.trim()
            if (normalizedCleared.isEmpty()) return HistoryClearRefreshAction.NO_OP
            if (normalizedDisplayed.isNotEmpty() && normalizedDisplayed == normalizedCleared) {
                return HistoryClearRefreshAction.REFRESH_DISPLAYED_SINGLE
            }
            if (normalizedDisplayed.isEmpty() && normalizedSelected == normalizedCleared) {
                return HistoryClearRefreshAction.REFRESH_SELECTED_SINGLE
            }
            return HistoryClearRefreshAction.NO_OP
        }

        /**
         * CHEVRON-COHERENCE: resolve the current live-head coordinate the camera should follow.
         *
         * The camera, the marker, and the line must advance from the same datum. The marker
         * reads `state.trail.lastOrNull()`; binding the camera to that same tail when it
         * represents the active recording session prevents the runtime-store collector from
         * publishing a frame with a fresher camera target than the bus-reducer-driven trail
         * tail (which the user perceives as the chevron lagging while the world moves on).
         *
         * Falls through to `runtime.lastTrackedLatitude/Longitude` when there is no active
         * session trail tail (e.g. just-started recording, mid-mode-switch, or a stale
         * prior-session trail still in state). Returns null when neither is available.
         */
        @JvmStatic
        internal fun resolveLiveHeadCoord(state: TrackerMapUiState): Pair<Double, Double>? {
            val displayedTrackerId = TrackerMapDisplayIds.effectiveDisplayedTrackerId(state)
            val overlaidTrail = singleTrailWithLocalRuntimeOverlay(
                mode = state.mode,
                runtime = state.runtime,
                displayedTrackerId = displayedTrackerId,
                trail = state.trail,
            )
            overlaidTrail.lastOrNull()?.let { return it.latitude to it.longitude }
            val runtimeLat = state.runtime.lastTrackedLatitude
            val runtimeLon = state.runtime.lastTrackedLongitude
            if (runtimeLat != null && runtimeLon != null && state.runtime.lastTrackedTimestampMs > 0L) {
                return runtimeLat to runtimeLon
            }
            return null
        }

        @JvmStatic
        internal fun allQueueTrailsWithLocalRuntimeOverlay(
            mode: TrackerMapDisplayMode,
            runtime: TrackingRuntimeSnapshot,
            groupTrackerIds: Set<String>,
            allQueueTrailsByTracker: Map<String, List<QueuedLocation>>,
        ): Map<String, List<QueuedLocation>> {
            return TrackerMapEffectiveSessionProjector.allQueueTrailsWithLocalRuntimeOverlay(
                mode = mode,
                runtime = runtime,
                groupTrackerIds = groupTrackerIds,
                allQueueTrailsByTracker = allQueueTrailsByTracker,
                trailPointLimit = TRAIL_POINT_LIMIT,
            )
        }

        @JvmStatic
        internal fun singleTrailWithLocalRuntimeOverlay(
            mode: TrackerMapDisplayMode,
            runtime: TrackingRuntimeSnapshot,
            displayedTrackerId: String,
            trail: List<QueuedLocation>,
        ): List<QueuedLocation> {
            return TrackerMapEffectiveSessionProjector.singleTrailWithLocalRuntimeOverlay(
                mode = mode,
                runtime = runtime,
                displayedTrackerId = displayedTrackerId,
                trail = trail,
                trailPointLimit = TRAIL_POINT_LIMIT,
            )
        }

        /**
         * STREAMING-RESUME GUARD: pure helper for evaluateResumeAfterBackground's
         * short-circuit. Returns true when the live-stream runtime is currently subscribed to
         * exactly the trackers the displayed mode wants, after applying the streaming exclusion
         * for the locally-recorded tracker.
         */
        @JvmStatic
        internal fun streamingActiveTargetsMatchDisplayed(
            mode: TrackerMapDisplayMode,
            displayedIds: Set<String>,
            localRecordingActive: Boolean,
            locallyRecordedTrackerId: String,
            activeStreamTargets: Set<String>,
        ): Boolean {
            if (mode != TrackerMapDisplayMode.GROUP_PLACEHOLDER && mode != TrackerMapDisplayMode.ALL_QUEUE) {
                return false
            }
            val excluded = if (localRecordingActive && locallyRecordedTrackerId.isNotBlank()) {
                setOf(locallyRecordedTrackerId.trim())
            } else {
                emptySet()
            }
            val expected = StreamingTargetPolicy.normalizeTrackerIds(displayedIds - excluded)
            if (expected.isEmpty()) return false
            val active = StreamingTargetPolicy.normalizeTrackerIds(activeStreamTargets)
            return active == expected
        }

        /**
         * STREAMING-RESUME GUARD: pure helper for evaluateResumeAfterBackground's short-circuit.
         * Returns true only when EVERY displayed roster tracker has at least one
         * `PROVENANCE_SERVER_GEOMETRY` point. A loose "is the entry non-empty?" check is
         * insufficient because a sliver of local-queue or live-stream rows looks "loaded" but
         * is missing the historical geometry the user expects — which causes the resume path
         * to skip the very reload that would heal a previously-broken state.
         */
        @JvmStatic
        internal fun displayedRosterHasServerHistory(
            mode: TrackerMapDisplayMode,
            rosterIds: Set<String>,
            allQueueTrailsByTracker: Map<String, List<QueuedLocation>>,
        ): Boolean {
            if (mode != TrackerMapDisplayMode.GROUP_PLACEHOLDER && mode != TrackerMapDisplayMode.ALL_QUEUE) {
                return false
            }
            val normalizedRosterIds = rosterIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            if (normalizedRosterIds.isEmpty()) return false
            return normalizedRosterIds.all { id ->
                allQueueTrailsByTracker[id].orEmpty().any(TrackerMapPointProvenancePolicy::isServerHistory)
            }
        }

        @JvmStatic
        internal fun resolveBottomCardVisibilityForMarkerTap(
            hasSelectionCard: Boolean
        ): Boolean {
            return hasSelectionCard
        }

        @JvmStatic
        internal fun resolveBackgroundTapShouldCloseBottomCard(
            isBottomCardVisible: Boolean,
            hasSelectionCard: Boolean
        ): Boolean {
            return isBottomCardVisible || hasSelectionCard
        }

        @JvmStatic
        internal fun resolveRenderSelectedMapTrackerId(
            isBottomCardVisible: Boolean,
            selectedMapTrackerId: String?
        ): String? {
            return selectedMapTrackerId
                ?.trim()
                ?.takeIf { isBottomCardVisible && it.isNotEmpty() }
        }

        @JvmStatic
        internal fun resolveFocusActionVisible(mode: TrackerMapDisplayMode): Boolean {
            return mode != TrackerMapDisplayMode.SINGLE_SESSION
        }

        @JvmStatic
        internal fun filterRemoteLastPointsForAcceptedIds(
            remoteLastPoints: Map<String, TrackPointEvent>,
            acceptedRemoteTrackerIds: Set<String>,
        ): Map<String, TrackPointEvent> {
            val acceptedIds = acceptedRemoteTrackerIds
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            // Empty acceptance commonly occurs during transient projection/service-lag frames
            // (e.g. mode transitions, tracking start mid-stream). Wiping all heads here would flicker
            // remote markers off the map. Callers explicitly clear remoteLastPoints when streaming
            // is fully STOPPED with no targets; here we preserve the previous heads so they remain
            // visible until a settled non-empty projection trims keys deterministically.
            if (acceptedIds.isEmpty()) return remoteLastPoints
            return remoteLastPoints.filterKeys { it.trim() in acceptedIds }
        }
    }

    private val appContext = application.applicationContext
    private val streamingReconciler = LiveTrackStreamingReconciler(appContext)
    private val dao = AppDatabase.getDatabase(application).locationDao()
    private val trackerManagementRepository: TrackerManagementRepository =
        TrackerAppServices.from(application).trackerManagementRepository()
    private val trackerManagementStateStore: TrackerManagementStateStore =
        TrackerAppServices.from(application).trackerManagementStateStore()
    private val trackerSettingsRepository: TrackerSettingsRepository =
        TrackerAppServices.from(application).trackerSettingsRepository()

    private val _uiState = MutableStateFlow(TrackerMapUiState())
    val uiState: StateFlow<TrackerMapUiState> = _uiState.asStateFlow()
    private val _renderPackage = MutableStateFlow(TrackerMapRenderPackage())
    val renderPackage: StateFlow<TrackerMapRenderPackage> = _renderPackage.asStateFlow()

    /**
     * CAMERA-DIRECTIVE: stable resolved camera target consumed by `MapScreen`. Replaces the prior
     * stack of overlapping `LaunchedEffect`s; the directive id increments only when the resolved
     * camera target changes meaningfully so equal back-to-back resolutions don't re-animate.
     */
    private val _cameraDirective = MutableStateFlow<TrackerMapCameraDirective>(TrackerMapCameraDirective.None())
    val cameraDirective: StateFlow<TrackerMapCameraDirective> = _cameraDirective.asStateFlow()
    private var lastCameraResolution: TrackerMapCameraDirectivePolicy.Resolution =
        TrackerMapCameraDirectivePolicy.Resolution.None
    private var nextCameraDirectiveId: Long = 1L

    fun trackerRosterForMapChip(): List<Tracker> = trackerManagementStateStore.trackers.value

    private val fitTrailSignal = Channel<TrackerMapFitTrailMode>(Channel.CONFLATED)
    private val pointEventChannel = Channel<TrackPointEvent>(Channel.UNLIMITED)
    val fitTrailEvents = fitTrailSignal.receiveAsFlow()
    private var lastStreamTargetsSeed: String? = null
    private var lastBackgroundAtElapsedMs: Long = 0L
    private var mapReady: Boolean = false
    private var pendingResumeEvaluation: Boolean = false
    private var mapSurfaceVisible: Boolean = false
    private var pendingInitialTrackerForMap: Boolean = true
    private var runtimeTrailReloadJob: Job? = null
    /**
     * COALESCING: holds the strongest reload reason requested while a reload was already in
     * flight. `null` means nothing pending. The merge in [TrackerMapTrailReloadReason.mergedWith]
     * picks force > multi-server > non-force, so a non-force request landing during a
     * forced reload never silently downgrades the next iteration.
     */
    private var runtimeTrailReloadPendingReason: TrackerMapTrailReloadReason? = null
    private val trailReloadMutex = Mutex()
    private var lastTrailLoadSeed: String? = null
    private var pendingReopenSingleTrackerLoadId: String? = null
    private var pendingFitAfterReload: Boolean = false
    /**
     * Detects per-tracker `recent_data_window` changes and produces a [TrackerMapFilterChangeReactor.FilterChange]
     * decision for the events collector. Seeded from the store snapshot at the end of init so
     * the very first edit isn't mistaken for a first observation. Replaces the previous inline
     * change-detection memo whose null-baseline branch caused some edits to silently skip
     * the reload path.
     */
    private val filterChangeReactor = TrackerMapFilterChangeReactor()
    private var lastObservedTrackingRunning: Boolean? = null
    private var lastObservedLocalRecordingActive: Boolean? = null
    private val clearedHistoryTrackerIds: MutableSet<String> = mutableSetOf()

        /**
         * STREAM-STATE-MACHINE: tracks whether the previous snapshot represented an active streaming
         * session (intent expressed and orchestrator not yet terminal). Used to detect the
         * active -> ended transition that triggers map-lease cleanup. Supersedes the older
         * single `isRunning` signal — derived from `wantsSubscription` / `subscriptionEnded`.
         */
    private var lastObservedStreamingSessionActive: Boolean = false
    private var lastObservedStreamingFailureReason: String? = null

    /**
     * COMBINED-RECONCILE: a monotonic version counter included in the combined-reconcile seed.
     * Calling [bumpReconcileToken] forces the next emission through `distinctUntilChangedBy` even
     * if [_uiState] and [LiveStreamRuntimeStateStore.state] are otherwise identical to the prior
     * tick. This replaces [LiveTrackStreamingReconciler.invalidateDedupe], which previously had to
     * reach into the reconciler to clear an internal seed.
     */
    private val _reconcileToken = MutableStateFlow(0L)
    private val runtimeResyncPolicy = TrackerMapRuntimeResyncPolicy()
    private val reopenOrchestrator = TrackerMapReopenOrchestrator()
    private val sessionRequestDeduper = TrackerMapSessionRequestDeduper()
    private val geometryLoadingTracker = TrackerMapGeometryLoadingTracker(
        onLoadingChanged = ::setGeometryLoading
    )
    private val trailLoaderOps = TrackerMapTrailLoaderOps(
        loadSingleServer = { trackerId, existingTrailMinTimeMs ->
            loadSingleTrackerTrailFromServer(trackerId, existingTrailMinTimeMs)
        },
        loadMultiServer = { trackerIds, existingMultiMinTimes ->
            loadTrailsForTrackerIds(trackerIds, existingMultiMinTimes)
        },
        loadQueue = { trackerId -> loadQueueTrail(trackerId) },
    )

    init {
        SelectedTrackerManager.syncRuntimeSelectedTracker(application)
        // PRELOAD-AT-INIT: read the persisted selected tracker id straight from prefs and
        // seed `_uiState.trail` from the local Room queue ASAP. This races (intentionally)
        // with the first `_uiState.collect` below so the very first render package the map
        // sees already has a trail to fit the camera to. Without this, the launch sequence
        // is "empty render -> 0,0 flash -> ExplicitTrackerLoad -> server fetch -> snap"
        // because `getTrackers()` is metadata-only (no geometry) so the in-memory cache
        // preload inside `reloadTrailFromDatabaseLocked` returns null on every cold launch.
        // The Room queue is the only source of truth that survives process death without
        // a network round-trip.
        viewModelScope.launch {
            seedInitialTrailFromLocalQueue()
        }
        viewModelScope.launch {
            _uiState.collect {
                publishRenderPackage()
            }
        }
        viewModelScope.launch {
            trackerSettingsRepository.observeSettings()
                .map { it.groupModeFitOnlyActiveTrackers }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    publishRenderPackage()
                }
        }
        viewModelScope.launch {
            TrackingRuntimeStateStore.state.collect { snap ->
                val effectiveLifecycleState = if (!snap.isRunning && snap.startupActive) {
                    TrackingLifecycleState.STARTING
                } else {
                    snap.lifecycleState
                }
                val effectiveRuntime = snap.copy(
                    isRunning = snap.isRunning,
                    lifecycleState = effectiveLifecycleState
                )
                val current = _uiState.value
                val displayedTrackerId = if (current.displayedTrackerId.isBlank()) {
                    effectiveRuntime.selectedTrackerId
                } else {
                    current.displayedTrackerId
                }
                val displayedTrackerName = if (current.displayedTrackerName.isBlank()) {
                    effectiveRuntime.selectedTrackerName
                } else {
                    current.displayedTrackerName
                }
                _uiState.value = current.copy(
                    runtime = effectiveRuntime,
                    displayedTrackerId = displayedTrackerId,
                    displayedTrackerName = displayedTrackerName
                )
                GeoVaultCaptureLog.d(
                    TAG,
                    "map_update vm_runtime_snapshot mode=${current.mode} selected=${snap.selectedTrackerId.trim()} " +
                        "localActive=${snap.localRecordingActive} localId=${snap.locallyRecordedTrackerId.trim()} " +
                        "sessionStart=${snap.sessionStartTimeMs} lastTs=${snap.lastTrackedTimestampMs} " +
                        "lat=${snap.lastTrackedLatitude} lon=${snap.lastTrackedLongitude} " +
                        "displayed=$displayedTrackerId trail=${current.trail.size} multi=${current.allQueueTrailsByTracker.mapSizes()}"
                )
                val prevLocalRecording = lastObservedLocalRecordingActive
                lastObservedLocalRecordingActive = snap.localRecordingActive
                if (prevLocalRecording != null && !prevLocalRecording && snap.localRecordingActive) {
                    val afterRuntime = _uiState.value
                    when (
                        val autoLock = TrackerMapAutoLockPolicy.resolveAutoLockOnRecordingStart(
                            mode = afterRuntime.mode,
                            displayedTrackerId = afterRuntime.displayedTrackerId,
                            selectedTrackerId = afterRuntime.runtime.selectedTrackerId,
                        )
                    ) {
                        is TrackerMapAutoLockOnRecordingResult.SelectionLock -> {
                            _uiState.update {
                                it.withAllMapLocksDisabled().copy(selectionLockTrackerId = autoLock.trackerId)
                            }
                        }
                        TrackerMapAutoLockOnRecordingResult.LiveActiveFit -> {
                            _uiState.update {
                                it.withAllMapLocksDisabled().copy(liveActiveFitEnabled = true)
                            }
                            publishRenderPackage()
                            requestFitTrail()
                        }
                        TrackerMapAutoLockOnRecordingResult.None -> Unit
                    }
                }
                val runtimeResyncDecision = runtimeResyncPolicy.decide(
                    previousIsRunning = lastObservedTrackingRunning,
                    currentIsRunning = snap.isRunning,
                    mapReady = mapReady,
                    mapViewContext = if (current.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
                        TrackerMapViewContext.GROUP
                    } else {
                        TrackerMapViewContext.SINGLE_TRACKER
                    }
                )
                lastObservedTrackingRunning = snap.isRunning
                // RECORDING-DELTA RELOAD: a localRecordingActive transition flips the
                // streaming exclusion (locally-recorded id is added/removed from
                // remoteSubscriptionIds) AND the trail merge plan (overlayTrackerId set
                // becomes non-empty/empty). Both demand a forced server refetch so the
                // multi-trail's locally-recorded entry is rebuilt with real geometry instead
                // of relying on `refreshStreamTargets`'s subscription-set delta firing
                // StreamingStart as a side effect. Cosmetic ticks remain GenericMapRefresh.
                val recordingTransitioned = prevLocalRecording != null &&
                    prevLocalRecording != snap.localRecordingActive
                val reloadReason = if (recordingTransitioned) {
                    TrackerMapTrailReloadReason.StreamingStart
                } else {
                    TrackerMapTrailReloadReason.GenericMapRefresh
                }
                GeoVaultCaptureLog.d(
                    TAG,
                    "map_update vm_runtime_reload_request reason=$reloadReason recordingTransitioned=$recordingTransitioned " +
                        "prevLocal=$prevLocalRecording currentLocal=${snap.localRecordingActive}"
                )
                requestRuntimeTrailReload(reloadReason)
                refreshStreamTargets()
                if (runtimeResyncDecision.restartDisplayedStreaming) {
                    bumpReconcileToken()
                }
                if (pendingInitialTrackerForMap && mapReady && mapSurfaceVisible) {
                    evaluateResumeAfterBackground(allowZeroGap = true)
                }
            }
        }
        viewModelScope.launch {
            TrackPointBus.events.collect { point ->
                GeoVaultCaptureLog.d(
                    TAG,
                    "map_update vm_bus_collect source=${point.source} track=${point.trackId.trim()} " +
                        "ts=${point.timestampMs} order=${point.orderingKey} lat=${point.lat} lon=${point.lon}"
                )
                pointEventChannel.send(point)
            }
        }
        // COMBINED-RECONCILE: this collector handles _state-mutation_ side effects of streaming
        // runtime updates only (mirroring active ids, trimming remote heads, recomputing the
        // status pill, and emitting post-stop lease cleanup). The reconcile call itself moves to
        // the combined flow below so reconcile inputs come from a single coherent snapshot.
        viewModelScope.launch {
            LiveStreamRuntimeStateStore.state.collectLatest { snapshot ->
                GeoVaultCaptureLog.d(
                    TAG,
                    "map_update vm_stream_snapshot wants=${snapshot.wantsSubscription} ended=${snapshot.subscriptionEnded} " +
                        "health=${snapshot.health} active=${snapshot.activeTrackerIds.sorted()} failure=${snapshot.failureReason}"
                )
                // STREAM-STATE-MACHINE: an active session = the user/app expressed intent AND the
                // orchestrator hasn't terminated (cleanly stopped or permanently failed). The
                // active -> ended transition is what triggers lease cleanup below.
                val sessionActive = snapshot.wantsSubscription && !snapshot.subscriptionEnded
                val wasActive = lastObservedStreamingSessionActive
                val hadMapStreamingLease = streamingReconciler.hasMapStreamingLease()
                lastObservedStreamingSessionActive = sessionActive
                _uiState.update { current ->
                    val plan = projectSession(
                        state = current.copy(activeStreamedTrackerIds = snapshot.activeTrackerIds),
                        groupSelection = resolveGroupModeSelection(current),
                        visibleRosterTrackerIds = visibleMapRosterTrackerIds(),
                    )
                    current.copy(
                        activeStreamedTrackerIds = snapshot.activeTrackerIds,
                        remoteLastPoints = if (
                            snapshot.subscriptionEnded &&
                            current.streamTargetIds.isEmpty()
                        ) {
                            emptyMap()
                        } else {
                            filterRemoteLastPointsForAcceptedIds(
                                remoteLastPoints = current.remoteLastPoints,
                                acceptedRemoteTrackerIds = plan.acceptedRemoteTrackerIds,
                            )
                        },
                        streamingStatus = TrackerMapStreamingStatusPolicy.resolve(
                            snapshot = snapshot,
                            streamTargetIds = current.streamTargetIds,
                        ),
                    )
                }
                // STREAM-STATE-MACHINE: lease cleanup keys off subscriptionEnded (Stopped OR
                // FailedPermanent) rather than only Stopped, so an auth-burned-out streaming
                // session deterministically falls back to the user's selected tracker instead of
                // leaving the map staring at the failed group.
                if ((wasActive || hadMapStreamingLease) &&
                    snapshot.subscriptionEnded &&
                    streamingReconciler.consumeStoppedMapStreamingLease()
                ) {
                    restoreSelectedTrackerAfterStreamingStop()
                }
                // STREAM-FAILURE-INVALIDATE: a fresh failure reason should re-trigger reconcile so
                // any cleared dedupe in the coordinator can dispatch the next Start cleanly.
                val failureReason = snapshot.failureReason
                val previousFailure = lastObservedStreamingFailureReason
                if (failureReason != null && failureReason != previousFailure) {
                    bumpReconcileToken()
                }
                lastObservedStreamingFailureReason = failureReason
            }
        }
        // FINGERPRINT-DRIVEN REFRESH: pair (previous, current) fingerprints so we can decide
        // whether the change was cosmetic (name/color only — render-only republish) or
        // structural (roster, group membership, per-tracker hidden, map visibility — server
        // refetch). `recent_data_window` is deliberately excluded from both axes and handled
        // by [filterChangeReactor] instead, so a filter edit goes through exactly one path
        // and the cosmetic/structural axes don't double-fire on the same upsert.
        filterChangeReactor.seed(trackerManagementStateStore.trackers.value)
        viewModelScope.launch {
            combine(
                trackerManagementStateStore.trackers,
                trackerManagementStateStore.groups,
                trackerManagementStateStore.mapVisibility,
            ) { trackers, groups, visibility ->
                TrackerMapRenderMetadataFingerprint.from(trackers, groups, visibility)
            }
                .distinctUntilChanged()
                .scan<TrackerMapRenderMetadataFingerprint, Pair<TrackerMapRenderMetadataFingerprint?, TrackerMapRenderMetadataFingerprint?>>(
                    null to null
                ) { acc, next -> acc.second to next }
                .drop(1)
                .collectLatest { (previous, current) ->
                    current ?: return@collectLatest
                    _uiState.value = _uiState.value.copy(renderMetadataSignature = current.combined)
                    val structuralChanged = previous == null || previous.structural != current.structural
                    val reason = if (structuralChanged) {
                        TrackerMapTrailReloadReason.RosterChanged
                    } else {
                        TrackerMapTrailReloadReason.MetadataMapRefresh
                    }
                    requestRuntimeTrailReload(reason)
                    refreshStreamTargets()
                }
        }
        // Events are discrete, per-tracker side effects (invalidate cache + render +
        // request reload). Using `collect` instead of `collectLatest` here is deliberate:
        // collectLatest cancels the previous handler when a new event lands, which can
        // abort a suspending `sessionRequestDeduper.invalidate(...)` mid-flight. The
        // reactor's `observe` already mutated its baseline synchronously by that point,
        // so a cancelled invalidate would silently leak stale cache entries with no
        // second chance to purge them. Ordered draining keeps the contract simple.
        viewModelScope.launch {
            trackerManagementStateStore.events.collect { event ->
                when (event) {
                    is com.geovault.tracker.data.TrackerManagementEvent.HistoryCleared -> {
                        val state = _uiState.value
                        GeoVaultCaptureLog.i(
                            TAG,
                            "map_update vm_history_cleared_event track=${event.trackerId.trim()} " +
                                "mode=${state.mode} displayed=${state.displayedTrackerId.trim()} selected=${state.runtime.selectedTrackerId.trim()}"
                        )
                        when (
                            resolveHistoryClearRefreshAction(
                                mode = state.mode,
                                displayedTrackerId = state.displayedTrackerId,
                                selectedTrackerId = state.runtime.selectedTrackerId,
                                clearedTrackerId = event.trackerId
                            )
                        ) {
                            HistoryClearRefreshAction.REFRESH_GROUP_OR_ALL,
                            HistoryClearRefreshAction.REFRESH_DISPLAYED_SINGLE,
                            HistoryClearRefreshAction.REFRESH_SELECTED_SINGLE -> {
                                val clearedTrackerId = event.trackerId.trim()
                                if (clearedTrackerId.isNotEmpty()) {
                                    clearedHistoryTrackerIds += clearedTrackerId
                                }
                                GeoVaultCaptureLog.i(
                                    TAG,
                                    "map_update vm_history_clear_apply track=$clearedTrackerId action=refresh " +
                                        "barriers=${clearedHistoryTrackerIds.sorted()}"
                                )
                                // Defeat the deduper's 4s window so any reload that races a
                                // post-clear fetch reaches the server and observes the
                                // trimmed geometry instead of replaying the pre-clear
                                // response from cache.
                                sessionRequestDeduper.invalidate(event.trackerId)
                                clearRenderedTrailsAfterHistoryCleared(event.trackerId)
                                requestRuntimeTrailReload(TrackerMapTrailReloadReason.HistoryCleared)
                            }
                            HistoryClearRefreshAction.NO_OP -> Unit
                        }
                    }
                    is com.geovault.tracker.data.TrackerManagementEvent.TrackerUpserted -> {
                        handleFilterChange(filterChangeReactor.observe(event.tracker))
                    }
                    is com.geovault.tracker.data.TrackerManagementEvent.TrackersRefreshed -> {
                        val changes = filterChangeReactor.observeAll(event.trackers)
                        for (change in changes) {
                            handleFilterChange(change)
                        }
                        if (changes.isNotEmpty()) refreshStreamTargets()
                    }
                    else -> Unit
                }
            }
        }
        viewModelScope.launch {
            for (point in pointEventChannel) {
                handleTrackPointEvent(point)
            }
        }
        // COMBINED-RECONCILE: the single source of truth for reconcile triggering. By combining
        // ui state, streaming runtime, and the explicit invalidation token into one flow we
        // eliminate the dual-collector race where one path could see a fresher uiState than the
        // other saw of streamRuntime (or vice versa). distinctUntilChangedBy on the seed dedupes
        // identical inputs without requiring an internal reconciler-side seed cache.
        viewModelScope.launch {
            combine(
                _uiState,
                LiveStreamRuntimeStateStore.state,
                _reconcileToken,
            ) { ui, stream, token -> ReconcileInputs(ui, stream, token) }
                .distinctUntilChangedBy { reconcileSeedKey(it.state, it.streamRuntime, it.token) }
                .collect { inputs -> reconcileStreaming(inputs.state, inputs.streamRuntime) }
        }
        refreshStreamTargets()
    }

    private data class ReconcileInputs(
        val state: TrackerMapUiState,
        val streamRuntime: LiveStreamRuntimeSnapshot,
        val token: Long,
    )

    private fun bumpReconcileToken() {
        _reconcileToken.value = _reconcileToken.value + 1L
    }

    /**
     * COMBINED-RECONCILE: stable string key that captures every input the reconciler reads. Two
     * adjacent ticks with the same key are deduped; any change here triggers exactly one
     * reconcile call.
     */
    private fun reconcileSeedKey(
        state: TrackerMapUiState,
        streamRuntime: LiveStreamRuntimeSnapshot,
        token: Long,
    ): String {
        val plan = projectSession(state)
        val streamIdsSignature = state.streamTargetIds.toList().sorted().joinToString(separator = ",")
        val activeIdsSignature = streamRuntime.activeTrackerIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .sorted()
            .joinToString(separator = ",")
        val trackingActiveOrStarting = state.runtime.localRecordingActive
        val selectedTrackerId = state.runtime.selectedTrackerId.trim()
        return "${state.mode}|$trackingActiveOrStarting|$streamIdsSignature|${plan.displayedTrackerId}|" +
            "$selectedTrackerId|${plan.displayedTrackerName}|" +
            "${streamRuntime.wantsSubscription}|${streamRuntime.health.name}|$activeIdsSignature|" +
            "${streamRuntime.failureReason.orEmpty()}|$token"
    }

    fun setMode(mode: TrackerMapDisplayMode) {
        val groupOptions = if (mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
            resolveGroupModeOptions()
        } else {
            emptyList()
        }
        val preferredGroupId = if (mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
            val currentGroup = _uiState.value.currentGroupId.trim()
            currentGroup.takeIf { candidate -> groupOptions.any { it.groupId == candidate } }
                ?: groupOptions.firstOrNull()?.groupId.orEmpty()
        } else {
            ""
        }
        val nextState = _uiState.value.copy(
            mode = mode,
            currentGroupId = preferredGroupId,
            groupModeOptions = groupOptions,
        )
        val pendingReopenTrackerId = if (mode == TrackerMapDisplayMode.SINGLE_SESSION) {
            pendingReopenSingleTrackerLoadId
        } else {
            null
        }
        applyMapContextTransition(
            nextState = nextState,
            pendingReopenTrackerId = pendingReopenTrackerId,
            reloadReason = TrackerMapTrailReloadReason.MapContextChange,
        )
    }

    fun setGroupModeGroup(groupId: String) {
        val normalized = groupId.trim()
        if (normalized.isEmpty()) return
        val state = _uiState.value
        if (state.currentGroupId == normalized && state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
            return
        }
        val nextState = state.copy(
            currentGroupId = normalized,
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
        )
        applyMapContextTransition(
            nextState = nextState,
            pendingReopenTrackerId = null,
            reloadReason = TrackerMapTrailReloadReason.MapContextChange,
        )
    }

    fun openTrackerOnMap(trackerId: String, trackerName: String?) {
        val normalizedId = trackerId.trim()
        if (normalizedId.isEmpty()) return
        val state = _uiState.value
        val resolvedName = trackerName?.trim().orEmpty().ifBlank {
            if (normalizedId == state.runtime.selectedTrackerId) {
                state.runtime.selectedTrackerName
            } else {
                trackerManagementStateStore.trackers.value
                    .firstOrNull { it.id == normalizedId }
                    ?.name
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: state.displayedTrackerName.takeIf { state.displayedTrackerId == normalizedId }
                    ?: normalizedId
            }
        }
        val nextState = state.copy(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = normalizedId,
            displayedTrackerName = resolvedName,
            currentGroupId = "",
            groupModeOptions = emptyList(),
        )
        applyMapContextTransition(
            nextState = nextState,
            pendingReopenTrackerId = normalizedId,
            reloadReason = TrackerMapTrailReloadReason.ExplicitTrackerLoad,
        )
    }

    fun openGroupOnMap(groupId: String) {
        val normalizedId = groupId.trim()
        if (normalizedId.isEmpty()) return
        val groupOptions = resolveGroupModeOptions()
        val resolvedGroupId = normalizedId.takeIf { candidate ->
            groupOptions.any { it.groupId == candidate }
        } ?: groupOptions.firstOrNull()?.groupId.orEmpty()
        val nextState = _uiState.value.copy(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            currentGroupId = resolvedGroupId,
            groupModeOptions = groupOptions,
        )
        applyMapContextTransition(
            nextState = nextState,
            pendingReopenTrackerId = null,
            reloadReason = TrackerMapTrailReloadReason.MapContextChange,
        )
    }

    fun restoreSelectedTrackerAfterStreamingStop() {
        restoreSelectedTrackerMapContext()
    }

    fun restoreSelectedTrackerMapContext() {
        val state = _uiState.value
        val selectedId = state.runtime.selectedTrackerId.trim()
        streamingReconciler.stopForegroundStreaming()
        // CHIP-X / POST-STREAM RESTORE: always collapse back to SINGLE_SESSION on the selected
        // tracker. Both entry points (the X on the top-left chip and the auto-restore that fires
        // when streaming ends) want a deterministic return to the user's selected tracker view.
        // Leaving the mode unchanged would only stop the foreground service and then immediately
        // reissue Start via the combined reconcile flow (since GROUP_PLACEHOLDER's
        // streamTargetIds are unchanged), producing a visible "reconnecting" flicker without
        // any actual exit from the group.
        if (selectedId.isBlank()) {
            pendingInitialTrackerForMap = true
            pendingResumeEvaluation = true
            return
        }
        val nextState = state.copy(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = selectedId,
            displayedTrackerName = state.runtime.selectedTrackerName,
            currentGroupId = "",
            groupModeOptions = emptyList(),
        )
        applyMapContextTransition(
            nextState = nextState,
            pendingReopenTrackerId = selectedId,
            reloadReason = TrackerMapTrailReloadReason.RestoreSelectedAfterStreaming,
        )
    }

    fun resolveListNavigationTarget(preferredTrackerIdOverride: String? = null): MapListNavigationTarget {
        val state = _uiState.value
        val preferredTrackerId = preferredTrackerIdOverride?.trim().orEmpty().ifBlank {
            TrackerMapDisplayIds.effectiveDisplayedTrackerId(state)
        }.ifBlank {
            state.runtime.selectedTrackerId.trim()
        }.ifBlank { "" }
        val preferredTrackerOwned = trackerManagementStateStore.trackers.value
            .firstOrNull { it.id == preferredTrackerId }
            ?.isOwner()
        val currentGroupOwned = trackerManagementStateStore.groups.value
            .firstOrNull { it.id == state.currentGroupId.trim() }
            ?.isOwner()
        return MapListNavigationPolicy.resolve(
            mode = state.mode,
            currentGroupId = state.currentGroupId,
            preferredTrackerId = preferredTrackerId,
            isCurrentGroupOwned = currentGroupOwned,
            isPreferredTrackerOwned = preferredTrackerOwned,
        )
    }

    fun onTrackerMarkerTapped(trackerId: String) {
        val normalizedTrackerId = trackerId.trim()
        if (normalizedTrackerId.isEmpty()) return
        val snapshot = buildCurrentSessionSnapshot()
        val state = snapshot.uiState
        val selection = buildSelectionCard(snapshot, normalizedTrackerId)
        if (selection == null) {
            _uiState.value = state.withClearedMapSelectionCard()
            return
        }
        _uiState.value = stateWithSelectionCard(state, selection)
    }

    fun onMapBackgroundTapped(): Boolean {
        val state = _uiState.value
        if (!resolveBackgroundTapShouldCloseBottomCard(
                isBottomCardVisible = state.isBottomCardVisible,
                hasSelectionCard = state.selectedMapTracker != null
            )
        ) {
            return false
        }
        _uiState.value = state.withClearedMapSelectionCard()
        return true
    }

    fun selectMapTrackerFromTap(trackerId: String) {
        onTrackerMarkerTapped(trackerId)
    }

    fun clearMapTrackerSelection() {
        onMapBackgroundTapped()
    }

    fun focusSelectedTrackerOnMap() {
        val state = _uiState.value
        val selection = state.selectedMapTracker ?: return
        openTrackerOnMap(selection.trackerId, selection.trackerName)
    }

    fun toggleSelectedTrackerLock() {
        val state = _uiState.value
        val selection = state.selectedMapTracker ?: return
        toggleTrackerLock(selection.trackerId)
    }

    fun toggleDisplayedTrackerLock() {
        val state = _uiState.value
        val displayedId = TrackerMapDisplayIds.effectiveDisplayedTrackerId(state)
        if (displayedId.isEmpty()) return
        toggleTrackerLock(displayedId)
    }

    private fun toggleTrackerLock(trackerId: String) {
        val selectedId = trackerId.trim()
        if (selectedId.isEmpty()) return
        val state = _uiState.value
        val nextSelectionLock = if (state.selectionLockTrackerId == selectedId) "" else selectedId
        _uiState.value = state.withAllMapLocksDisabled().copy(selectionLockTrackerId = nextSelectionLock)
    }

    fun selectionLockPointOrNull(): Pair<Double, Double>? {
        return selectionLockPointOrNull(buildCurrentSessionSnapshot())
    }

    private fun selectionLockPointOrNull(
        snapshot: TrackerMapSessionSnapshot
    ): Pair<Double, Double>? {
        val state = snapshot.uiState
        val trackerId = state.selectionLockTrackerId.trim()
        if (trackerId.isEmpty()) return null
        snapshot.tracks[trackerId]?.renderTrail?.lastOrNull()?.let { point ->
            return point.latitude to point.longitude
        }
        snapshot.acceptedRemoteLastPoints[trackerId]?.let { point ->
            return point.lat to point.lon
        }
        val point = resolveTrackerPointData(snapshot, trackerId) ?: return null
        return point.latitude to point.longitude
    }

    private fun buildSelectionCard(
        snapshot: TrackerMapSessionSnapshot,
        trackerId: String
    ): TrackerMapSelectionCard? {
        val state = snapshot.uiState
        val tracker = trackerManagementStateStore.trackers.value.firstOrNull { it.id == trackerId }
        val point = resolveTrackerPointData(snapshot, trackerId) ?: return null
        val trackerName = tracker?.name
            ?.takeIf { it.isNotBlank() }
            ?: state.displayedTrackerName.takeIf { trackerId == state.displayedTrackerId && it.isNotBlank() }
            ?: state.runtime.selectedTrackerName.takeIf { trackerId == state.runtime.selectedTrackerId && it.isNotBlank() }
            ?: trackerId
        return TrackerMapSelectionCard(
            trackerId = trackerId,
            trackerName = trackerName,
            latitude = point.latitude,
            longitude = point.longitude,
            lastUpdatedMs = TrackerLastReportedAtPolicy.resolve(
                trackerId = trackerId,
                runtime = state.runtime,
                resolverLastUpdatedMs = point.lastUpdatedMs,
            ),
            accuracyMeters = point.accuracyMeters,
            isOwned = tracker?.isOwner() == true,
            serverMetadataUpdatedAtMs = tracker?.let(TrackerPointTimestamps::serverMetadataUpdatedAtMs),
            lastPointParamsMs = tracker?.let(TrackerPointTimestamps::lastPointParamsMs),
        )
    }

    private fun resolveTrackerPointData(
        snapshot: TrackerMapSessionSnapshot,
        trackerId: String
    ): TrackerMapResolvedPoint? {
        val normalizedId = trackerId.trim()
        if (normalizedId.isEmpty()) return null
        val tracker = trackerManagementStateStore.trackers.value.firstOrNull { it.id == normalizedId }
        val effectiveState = snapshot.uiState.copy(
            trail = snapshot.singleTrail,
            allQueueTrailsByTracker = snapshot.renderTrailsByTracker,
            remoteLastPoints = snapshot.acceptedRemoteLastPoints,
        )
        return TrackerMapLastPointResolver.resolveRenderedMarkerPoint(
            state = effectiveState,
            trackerId = trackerId,
            tracker = tracker,
            acceptedRemoteTrackerIds = snapshot.plan.acceptedRemoteTrackerIds,
        )
    }

    private fun stateWithSelectionCard(
        state: TrackerMapUiState,
        selection: TrackerMapSelectionCard
    ): TrackerMapUiState {
        val nextSelectionLockId = state.selectionLockTrackerId.trim()
            .takeIf { it.isNotEmpty() && it == selection.trackerId }
            .orEmpty()
        return state.copy(
            isBottomCardVisible = resolveBottomCardVisibilityForMarkerTap(hasSelectionCard = true),
            selectedMapTracker = selection,
            selectionLockTrackerId = nextSelectionLockId,
        )
    }

    private fun stateWithClearedRenderedTrails(
        state: TrackerMapUiState,
        trackerId: String,
    ): TrackerMapUiState {
        val normalizedId = trackerId.trim()
        if (normalizedId.isEmpty()) return state
        val effectiveDisplayedId = TrackerMapDisplayIds.effectiveDisplayedTrackerId(state)
        val clearSingleTrail = state.mode == TrackerMapDisplayMode.SINGLE_SESSION &&
            (effectiveDisplayedId == normalizedId || state.runtime.selectedTrackerId.trim() == normalizedId)
        return state.copy(
            trail = if (clearSingleTrail) emptyList() else state.trail,
            allQueueTrailsByTracker = state.allQueueTrailsByTracker - normalizedId,
            remoteLastPoints = state.remoteLastPoints - normalizedId,
        )
    }

    private fun clearRenderedTrailsAfterHistoryCleared(trackerId: String) {
        _uiState.value = stateWithClearedRenderedTrails(_uiState.value, trackerId)
        lastTrailLoadSeed = null
    }

    private fun stateWithResetMapContext(
        state: TrackerMapUiState,
        preservedSingleTrackerId: String? = null,
    ): TrackerMapUiState {
        return TrackerMapContextResetPolicy.reset(
            TrackerMapContextResetInput(
                state = state,
                preservedSingleTrackerId = preservedSingleTrackerId,
            )
        )
            .withAllMapLocksDisabled()
            .withClearedMapSelectionCard()
    }

    private fun applyMapContextTransition(
        nextState: TrackerMapUiState,
        pendingReopenTrackerId: String?,
        reloadReason: TrackerMapTrailReloadReason = TrackerMapTrailReloadReason.GenericMapRefresh,
    ) {
        val preservedSingleTrackerId = if (reloadReason == TrackerMapTrailReloadReason.RestoreSelectedAfterStreaming) {
            pendingReopenTrackerId
        } else {
            null
        }
        _uiState.value = stateWithResetMapContext(
            state = nextState,
            preservedSingleTrackerId = preservedSingleTrackerId,
        )
        pendingReopenSingleTrackerLoadId = pendingReopenTrackerId
        pendingFitAfterReload = true
        lastTrailLoadSeed = null
        requestRuntimeTrailReload(reloadReason)
        refreshStreamTargets()
    }

    fun onHostPaused() {
        lastBackgroundAtElapsedMs = SystemClock.elapsedRealtime()
    }

    fun onHostResumed() {
        if (lastBackgroundAtElapsedMs <= 0L || !mapSurfaceVisible) return
        if (!mapReady) {
            pendingResumeEvaluation = true
            return
        }
        evaluateResumeAfterBackground(allowZeroGap = false)
    }

    fun onMapSurfaceVisible() {
        mapSurfaceVisible = true
        if (!mapReady) {
            pendingResumeEvaluation = pendingResumeEvaluation ||
                pendingInitialTrackerForMap ||
                lastBackgroundAtElapsedMs > 0L
            return
        }
        evaluateResumeAfterBackground(allowZeroGap = pendingInitialTrackerForMap || pendingResumeEvaluation)
        bumpReconcileToken()
    }

    fun onMapSurfaceHidden(markBackground: Boolean = false) {
        mapSurfaceVisible = false
        mapReady = false
        if (markBackground) {
            lastBackgroundAtElapsedMs = SystemClock.elapsedRealtime()
            pendingResumeEvaluation = true
        }
        bumpReconcileToken()
        viewModelScope.launch {
            sessionRequestDeduper.clear()
        }
    }

    fun setMapReady(isReady: Boolean) {
        mapReady = isReady
        if (!mapReady || !pendingResumeEvaluation) return
        pendingResumeEvaluation = false
        evaluateResumeAfterBackground(allowZeroGap = pendingInitialTrackerForMap)
    }

    private fun evaluateResumeAfterBackground(allowZeroGap: Boolean) {
        val backgroundDurationMs = if (lastBackgroundAtElapsedMs > 0L) {
            SystemClock.elapsedRealtime() - lastBackgroundAtElapsedMs
        } else {
            0L
        }
        if (backgroundDurationMs <= 0L && !allowZeroGap) return
        val state = _uiState.value
        val groupSelection = resolveGroupModeSelection(state)
        val hasPendingInitialTracker = pendingInitialTrackerForMap
        val selectedTrackerId = state.runtime.selectedTrackerId.trim()
        if (hasPendingInitialTracker &&
            state.mode == TrackerMapDisplayMode.SINGLE_SESSION &&
            selectedTrackerId.isBlank() &&
            TrackerMapDisplayIds.effectiveDisplayedTrackerId(state).isBlank()
        ) {
            pendingResumeEvaluation = true
            return
        }
        pendingInitialTrackerForMap = false
        val streamRuntime = LiveStreamRuntimeStateStore.state.value
        // STREAMING-RESUME NO-OP: when a group / all-queue stream is already running with the
        // right targets and we have populated trails for the displayed roster, the WS is the
        // authoritative source and the orchestrator's reload+reconcile pass would only cause
        // a visible "Reconnecting" flicker on resume. Trust the existing wiring; the combined
        // reconcile collector still validates the lease on the next state tick.
        if (
            streamRuntime.wantsSubscription &&
            streamRuntime.subscriptionHealthy &&
            (state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER ||
                state.mode == TrackerMapDisplayMode.ALL_QUEUE) &&
            streamingActiveTargetsMatchDisplayed(state, streamRuntime, groupSelection) &&
            displayedRosterHasServerHistory(state, groupSelection)
        ) {
            lastBackgroundAtElapsedMs = 0L
            pendingResumeEvaluation = false
            return
        }
        // STREAMING EXCLUSION (resume): the persisted ids are taken at face value. The projector
        // re-applies the only meaningful exclusion (locally-recorded) on the next reconcile pass;
        // pre-filtering selected here previously produced churn whenever resume and the projector
        // disagreed about whether selected belonged in the stream.
        val persistedStreamTargetIds = MapStreamingServiceHelper.persistedTargets(
            context = getApplication<Application>(),
        ).first
        val unsanitizedResumeStreamTrackerIds = if (streamRuntime.activeTrackerIds.isNotEmpty()) {
            streamRuntime.activeTrackerIds
        } else {
            state.activeStreamedTrackerIds + persistedStreamTargetIds
        }
        // STREAMING EXCLUSION (resume): just normalize the persisted ids. The projector / runtime
        // resync re-applies the only meaningful exclusion (locally-recorded) on the next reconcile
        // pass; pre-filtering the selected tracker here would silently drop our own tracker from a
        // persisted group / all-queue stream every time we resume from background.
        val resumeStreamTrackerIds = StreamingTargetPolicy.normalizeTrackerIds(unsanitizedResumeStreamTrackerIds)
        val outcome = reopenOrchestrator.resolve(
            TrackerMapResumeInput(
                trackingRunning = state.runtime.localRecordingActive,
                mapReady = mapReady,
                showAllTrackers = state.mode == TrackerMapDisplayMode.ALL_QUEUE,
                mapViewContext = if (state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
                    TrackerMapViewContext.GROUP
                } else {
                    TrackerMapViewContext.SINGLE_TRACKER
                },
                activeStreamedTrackerIds = resumeStreamTrackerIds,
                currentGroupTrackIds = if (state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
                    groupSelection.trackerIds
                } else {
                    emptySet()
                },
                selectedTrackerId = selectedTrackerId,
                displayedTrackerId = TrackerMapDisplayIds.effectiveDisplayedTrackerId(state),
                hasTrailPoints = state.trail.isNotEmpty(),
                hasPendingInitialTracker = hasPendingInitialTracker,
                backgroundedDurationMs = backgroundDurationMs
            )
        )
        outcome.invariants
            .filter { !it.satisfied }
            .forEach { invariant ->
                GeoVaultCaptureLog.w(TAG, "Reopen invariant violation ${invariant.invariant}: ${invariant.details}")
            }
        viewModelScope.launch {
            applyReopenDecision(outcome.decision)
            refreshStreamTargets()
            bumpReconcileToken()
            lastBackgroundAtElapsedMs = 0L
            pendingResumeEvaluation = false
        }
    }

    private fun streamingActiveTargetsMatchDisplayed(
        state: TrackerMapUiState,
        streamRuntime: LiveStreamRuntimeSnapshot,
        groupSelection: TrackerMapGroupModeSelection,
    ): Boolean {
        return streamingActiveTargetsMatchDisplayed(
            mode = state.mode,
            displayedIds = when (state.mode) {
                TrackerMapDisplayMode.GROUP_PLACEHOLDER -> groupSelection.trackerIds
                TrackerMapDisplayMode.ALL_QUEUE -> visibleMapRosterTrackerIds()
                else -> emptySet()
            },
            localRecordingActive = state.runtime.localRecordingActive,
            locallyRecordedTrackerId = state.runtime.locallyRecordedTrackerId,
            activeStreamTargets = streamRuntime.activeTrackerIds,
        )
    }

    private fun displayedRosterHasServerHistory(
        state: TrackerMapUiState,
        groupSelection: TrackerMapGroupModeSelection,
    ): Boolean {
        return displayedRosterHasServerHistory(
            mode = state.mode,
            rosterIds = when (state.mode) {
                TrackerMapDisplayMode.GROUP_PLACEHOLDER -> groupSelection.trackerIds
                TrackerMapDisplayMode.ALL_QUEUE -> visibleMapRosterTrackerIds()
                else -> emptySet()
            },
            allQueueTrailsByTracker = state.allQueueTrailsByTracker,
        )
    }

    private suspend fun applyReopenDecision(decision: TrackerMapResumeDecision) {
        when (decision) {
            TrackerMapResumeDecision.NoOp -> Unit
            TrackerMapResumeDecision.MultiContextNoStreaming -> Unit
            is TrackerMapResumeDecision.StartMultiContextStreaming -> {
                pendingReopenSingleTrackerLoadId = null
                val locallyRecordedTrackerId = _uiState.value.runtime.locallyRecordedTrackerId
                val ids = StreamingTargetPolicy.remoteSubscriptionTargets(
                    StreamingTargetPolicyInput(
                        requestedTrackerIds = decision.trackerIds,
                        locallyRecordedTrackerIds = setOfNotBlank(locallyRecordedTrackerId),
                    )
                )
                _uiState.update { cur ->
                    cur.copy(
                        streamTargetIds = ids,
                        remoteLastPoints = filterRemoteLastPointsForAcceptedIds(cur.remoteLastPoints, ids),
                    )
                }
                bumpReconcileToken()
            }
            TrackerMapResumeDecision.ClearSingleTrackerState -> {
                pendingReopenSingleTrackerLoadId = null
                // CONTEXT RESET: no tracker is displayed anymore, so any previously-set selection
                // lock is no longer meaningful — clear all map locks alongside the card.
                _uiState.update { cur ->
                    cur.copy(
                        displayedTrackerId = "",
                        displayedTrackerName = "",
                        remoteLastPoints = emptyMap(),
                        streamTargetIds = emptySet(),
                    ).withAllMapLocksDisabled().withClearedMapSelectionCard()
                }
                streamingReconciler.stopForegroundStreaming()
            }
            is TrackerMapResumeDecision.LoadSingleTrackerRuntime,
            is TrackerMapResumeDecision.LoadSingleTrackerBootstrap -> {
                val trackerId = when (decision) {
                    is TrackerMapResumeDecision.LoadSingleTrackerRuntime -> decision.trackerId
                    is TrackerMapResumeDecision.LoadSingleTrackerBootstrap -> decision.trackerId
                }
                pendingReopenSingleTrackerLoadId = trackerId.takeIf { it.isNotBlank() }
                if (trackerId.isNotBlank()) {
                    val runtime = _uiState.value.runtime
                    val trackerName = if (trackerId == runtime.selectedTrackerId) {
                        runtime.selectedTrackerName
                    } else {
                        _uiState.value.displayedTrackerName
                    }
                    // SWITCHING DISPLAYED TRACKER: when the resume decision points us at a
                    // different tracker than the one currently displayed, drop any selection lock
                    // tied to the previous tracker. Same-tracker bootstraps/runtime resyncs leave
                    // the lock alone so a user-set lock survives a benign resume.
                    val previousDisplayedTrackerId = _uiState.value.displayedTrackerId.trim()
                    val trackerChanged = trackerId.trim() != previousDisplayedTrackerId
                    _uiState.value = _uiState.value.copy(
                        displayedTrackerId = trackerId,
                        displayedTrackerName = trackerName,
                    ).let { next ->
                        if (trackerChanged) next.withAllMapLocksDisabled() else next
                    }.withClearedMapSelectionCard()
                }
                reloadTrailFromDatabase(TrackerMapTrailReloadReason.ExplicitTrackerLoad)
                if (pendingReopenSingleTrackerLoadId == trackerId) {
                    pendingReopenSingleTrackerLoadId = null
                }
                bumpReconcileToken()
            }
            TrackerMapResumeDecision.RestartDisplayedTrackerStreaming -> {
                pendingReopenSingleTrackerLoadId = null
                bumpReconcileToken()
            }
        }
    }

    fun setFollowLock(enabled: Boolean) {
        val state = _uiState.value
        _uiState.value = if (enabled) {
            state.withAllMapLocksDisabled().copy(followLockEnabled = true)
        } else {
            state.copy(followLockEnabled = false)
        }
    }

    fun disableAllMapLocks() {
        val state = _uiState.value
        if (!state.followLockEnabled && !state.liveActiveFitEnabled && state.selectionLockTrackerId.isEmpty()) {
            return
        }
        _uiState.value = state.withAllMapLocksDisabled()
    }

    fun setLiveActiveFit(enabled: Boolean) {
        val state = _uiState.value
        _uiState.value = if (enabled) {
            state.withAllMapLocksDisabled().copy(liveActiveFitEnabled = true)
        } else {
            state.copy(liveActiveFitEnabled = false)
        }
        if (enabled) {
            publishRenderPackage()
            requestFitTrail()
        }
    }

    fun requestFitTrail(mode: TrackerMapFitTrailMode = TrackerMapFitTrailMode.Animated) {
        fitTrailSignal.trySend(mode)
    }

    private fun publishRenderPackage() {
        val nowMs = System.currentTimeMillis()
        val effectiveSession = buildCurrentEffectiveSession(nowMs = nowMs)
        val snapshot = effectiveSession.snapshot
        val nextRenderState = buildMapRenderState(snapshot)
        val nextBounds = trailBoundsOrNull(snapshot, nowMs)
        val nextSelectionLockPoint = selectionLockPointOrNull(snapshot)
        GeoVaultCaptureLog.d(
            TAG,
            "map_update vm_render_package mode=${snapshot.mode} displayed=${snapshot.plan.displayedTrackerId} " +
                "selected=${snapshot.plan.selectedTrackerId} single=${snapshot.singleTrail.trailSummary()} " +
                "multi=${snapshot.renderTrailsByTracker.mapSizes()} remote=${snapshot.acceptedRemoteLastPoints.keys.sorted()} " +
                "liveHead=${effectiveSession.liveHead} bounds=${nextBounds.boundsSummary()} " +
                "selectionLock=${_uiState.value.selectionLockTrackerId.trim()} selectionPoint=$nextSelectionLockPoint"
        )
        _renderPackage.update { current ->
            // RENDER-COALESCE: every _uiState tick previously bumped `revision`, which made every
            // downstream collector (camera effects, polyline rerenders, marker refreshes) treat
            // every state change as a unique frame even when the rendered output was bit-identical.
            // Compare the structural fields and only mint a new revision when the visible scene
            // truly changes. Identity equality on `renderState` is fine because [MapRenderState] is
            // a data class produced from immutable inputs; pair it with bounds and selection-lock
            // coords for completeness.
            if (current.renderState == nextRenderState &&
                current.bounds == nextBounds &&
                current.selectionLockPoint == nextSelectionLockPoint
            ) {
                current
            } else {
                TrackerMapRenderPackage(
                    renderState = nextRenderState,
                    bounds = nextBounds,
                    selectionLockPoint = nextSelectionLockPoint,
                    revision = current.revision + 1L,
                )
            }
        }
        publishCameraDirective(
            state = snapshot.uiState,
            followTarget = effectiveSession.liveHead,
            bounds = nextBounds,
            selectionLockPoint = nextSelectionLockPoint,
        )
    }

    /**
     * CAMERA-DIRECTIVE: resolve the precedence-aware camera target and only mint a new directive
     * id when the resolution semantically changes. Equal back-to-back resolutions reuse the prior
     * directive (and therefore reuse the prior id), so a `LaunchedEffect(directive.id)` consumer
     * doesn't re-animate on noisy state changes.
     */
    private fun publishCameraDirective(
        state: TrackerMapUiState,
        followTarget: Pair<Double, Double>?,
        bounds: org.maplibre.android.geometry.LatLngBounds?,
        selectionLockPoint: Pair<Double, Double>?,
    ) {
        val resolution = TrackerMapCameraDirectivePolicy.resolve(
            TrackerMapCameraDirectiveInput(
                followLockEnabled = state.followLockEnabled,
                gpsCollecting = state.runtime.gpsCollecting,
                followTargetLat = followTarget?.first,
                followTargetLon = followTarget?.second,
                selectionLockEnabled = state.selectionLockTrackerId.trim().isNotEmpty(),
                selectionLockLat = selectionLockPoint?.first,
                selectionLockLon = selectionLockPoint?.second,
                liveActiveFitEnabled = state.liveActiveFitEnabled,
                bounds = bounds,
            )
        )
        GeoVaultCaptureLog.d(
            TAG,
            "map_update vm_camera_resolve mode=${state.mode} follow=${state.followLockEnabled} " +
                "gpsCollecting=${state.runtime.gpsCollecting} followTarget=$followTarget " +
                "selectionLock=${state.selectionLockTrackerId.trim()} selectionPoint=$selectionLockPoint " +
                "liveFit=${state.liveActiveFitEnabled} bounds=${bounds.boundsSummary()} reason=${resolution.reason} " +
                "center=${resolution.centerLat},${resolution.centerLon}"
        )
        if (resolution == lastCameraResolution) return
        lastCameraResolution = resolution
        val id = nextCameraDirectiveId++
        _cameraDirective.value = when (resolution.reason) {
            TrackerMapCameraDirective.Reason.SelectionLock,
            TrackerMapCameraDirective.Reason.FollowLock -> {
                val lat = resolution.centerLat
                val lon = resolution.centerLon
                if (lat != null && lon != null) {
                    TrackerMapCameraDirective.CenterPreserveZoom(
                        latitude = lat,
                        longitude = lon,
                        reason = resolution.reason,
                        id = id,
                    )
                } else {
                    TrackerMapCameraDirective.None(id = id)
                }
            }
            TrackerMapCameraDirective.Reason.LiveActiveFit,
            TrackerMapCameraDirective.Reason.InitialFit -> {
                val nextBounds = resolution.bounds
                if (nextBounds != null) {
                    TrackerMapCameraDirective.FitBounds(
                        bounds = nextBounds,
                        reason = resolution.reason,
                        id = id,
                    )
                } else {
                    TrackerMapCameraDirective.None(id = id)
                }
            }
            // ExplicitFit is routed through fitTrailEvents, not the directive flow.
            TrackerMapCameraDirective.Reason.ExplicitFit,
            TrackerMapCameraDirective.Reason.NoOp -> TrackerMapCameraDirective.None(id = id)
        }
    }

    private fun buildCurrentSessionSnapshot(nowMs: Long = System.currentTimeMillis()): TrackerMapSessionSnapshot {
        return buildCurrentEffectiveSession(nowMs).snapshot
    }

    private fun buildCurrentEffectiveSession(nowMs: Long = System.currentTimeMillis()): TrackerMapEffectiveSession {
        return buildEffectiveSessionForState(_uiState.value, nowMs)
    }

    private fun buildSessionSnapshotForState(
        state: TrackerMapUiState,
        nowMs: Long = System.currentTimeMillis(),
    ): TrackerMapSessionSnapshot {
        return buildEffectiveSessionForState(state, nowMs).snapshot
    }

    private fun buildEffectiveSessionForState(
        state: TrackerMapUiState,
        nowMs: Long = System.currentTimeMillis(),
    ): TrackerMapEffectiveSession {
        val groupSelection = resolveGroupModeSelection(state)
        val plan = projectSession(
            state = state,
            groupSelection = groupSelection,
            visibleRosterTrackerIds = visibleMapRosterTrackerIds(),
        )
        return TrackerMapEffectiveSessionProjector.project(
            TrackerMapEffectiveSessionInput(
                state = state,
                plan = plan,
                trailPointLimit = TRAIL_POINT_LIMIT,
                recentDataWindowByTracker = currentRecentDataWindowByTracker(),
                currentSessionStartByTracker = currentSessionStartByTracker(state),
                visibleTrackerIds = visibleTrackerIdsForSessionPlan(state, plan),
                nowMs = nowMs,
            )
        )
    }

    private fun visibleTrackerIdsForSessionPlan(
        state: TrackerMapUiState,
        plan: TrackerMapStreamingPlan,
    ): Set<String>? {
        // ROSTER FILTER: SINGLE_SESSION passes null so the engine renders every trail it has
        // (the displayed-tracker logic is single-trail anyway and doesn't iterate the `tracks`
        // map). ALL_QUEUE passes `plan.visibleRosterTrackerIds`, which has already been run
        // through [HiddenMapItemsPolicy.visibleTrackerIdsForMap]. GROUP_PLACEHOLDER takes the
        // group's raw member list and intersects it with the hidden-filtered roster here — the
        // projector copies group members verbatim and would otherwise let a hidden group member
        // render. Passing an empty (but non-null) set is meaningful: "every group member is
        // hidden, render nothing", which the engine honors.
        return when (state.mode) {
            TrackerMapDisplayMode.GROUP_PLACEHOLDER -> HiddenMapItemsPolicy
                .visibleTrackerIdsForMap(
                    rosterTrackerIds = plan.groupTrackerIds,
                    mapVisibility = trackerManagementStateStore.mapVisibility.value,
                    trackers = trackerManagementStateStore.trackers.value,
                )
            TrackerMapDisplayMode.ALL_QUEUE -> plan.visibleRosterTrackerIds
            TrackerMapDisplayMode.SINGLE_SESSION -> null
        }
    }

    private fun currentRecentDataWindowByTracker(): Map<String, String?> {
        return trackerManagementStateStore.trackers.value.associate { tracker ->
            tracker.id to tracker.settingString("recent_data_window")
        }
    }

    private suspend fun handleFilterChange(change: TrackerMapFilterChangeReactor.FilterChange) {
        when (change) {
            is TrackerMapFilterChangeReactor.FilterChange.None -> Unit
            is TrackerMapFilterChangeReactor.FilterChange.Refresh -> {
                // Filter changed for a tracker we know. Invalidate the geometry dedupe entry
                // first so the imminent reload reaches the server with the new window; then
                // republish the render package for instant client-side re-filter on points we
                // already hold; then request the forced reload that will overwrite those with the
                // server's window-bounded response.
                sessionRequestDeduper.invalidate(change.trackerId)
                publishRenderPackage()
                requestRuntimeTrailReload(TrackerMapTrailReloadReason.RecentDataWindowChanged)
            }
        }
    }

    /**
     * Authoritative current-session start, keyed by tracker id. Populated only for the
     * locally-recorded tracker (the only tracker whose session boundary the client knows
     * authoritatively); foreign trackers are absent and fall back to per-point starttimestamps.
     */
    private fun currentSessionStartByTracker(state: TrackerMapUiState): Map<String, Long> {
        if (!state.runtime.localRecordingActive) return emptyMap()
        val sessionStart = state.runtime.sessionStartTimeMs
        if (sessionStart <= 0L) return emptyMap()
        val trackerId = state.runtime.locallyRecordedTrackerId.trim()
        if (trackerId.isEmpty()) return emptyMap()
        return mapOf(trackerId to sessionStart)
    }

    fun buildMapRenderState(): com.geovault.common.maps.render.MapRenderState {
        val snapshot = buildCurrentSessionSnapshot()
        return buildMapRenderState(snapshot)
    }

    private fun buildMapRenderState(
        snapshot: TrackerMapSessionSnapshot
    ): com.geovault.common.maps.render.MapRenderState {
        val s = snapshot.uiState
        val renderAllQueueTrailsByTracker = snapshot.renderTrailsByTracker
        val trackerColors = trackerManagementStateStore.trackers.value.associate { it.id to (it.color ?: "") }
        val trackerDisplayNames = trackerManagementStateStore.trackers.value.associate { it.id to it.name }
        val trackerRenderOrder = trackerManagementStateStore.trackers.value.map { it.id }
        val effectiveDisplayedId = TrackerMapDisplayIds.effectiveDisplayedTrackerId(s)
        val effectiveMapState = s.copy(
            trail = snapshot.singleTrail,
            allQueueTrailsByTracker = renderAllQueueTrailsByTracker,
            remoteLastPoints = snapshot.acceptedRemoteLastPoints,
        )
        val fallbackAccuracyByTrackerId = buildFallbackAccuracyByTrackerId(effectiveMapState, snapshot.plan)
        val visibleTrackerIds = resolveVisibleAccuracyTrackerIds(
            effectiveMapState,
            effectiveDisplayedId,
        )
        val allowAccuracyFallbackByTrackerId = TrackerAccuracyFallbackPolicy.resolveAllowedFallbackTrackerIds(
            TrackerAccuracyFallbackPolicyInput(
                mode = s.mode,
                runtimeRunning = s.runtime.localRecordingActive,
                selectedTrackerId = s.runtime.selectedTrackerId,
                displayedTrackerId = effectiveDisplayedId,
                visibleTrackerIds = visibleTrackerIds,
            )
        )
        return TrackerMapStateTransforms.buildRenderState(
            session = snapshot,
            cosmetics = TrackerMapRenderCosmetics(
                trackerColorById = trackerColors,
                trackerDisplayNameById = trackerDisplayNames,
                selectedMapTrackerId = resolveRenderSelectedMapTrackerId(
                    isBottomCardVisible = s.isBottomCardVisible,
                    selectedMapTrackerId = s.selectedMapTracker?.trackerId
                ),
                trackerRenderOrder = trackerRenderOrder,
                defaultIconColorHex = GeoVaultColorTokens.Hex.Blue400,
            ),
            accuracy = TrackerMapAccuracyRenderModel(
                fallbackAccuracyByTrackerId = fallbackAccuracyByTrackerId,
                allowAccuracyFallbackByTrackerId = allowAccuracyFallbackByTrackerId,
            ),
        )
    }

    fun trailBoundsOrNull(): LatLngBounds? {
        val snapshot = buildCurrentSessionSnapshot()
        return trailBoundsOrNull(snapshot, System.currentTimeMillis())
    }

    private fun trailBoundsOrNull(
        snapshot: TrackerMapSessionSnapshot,
        nowMs: Long,
    ): LatLngBounds? {
        val s = snapshot.uiState
        if (s.mode == TrackerMapDisplayMode.ALL_QUEUE || s.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
            val sessionPlan = snapshot.plan
            val visibleTrackerIds = visibleTrackerIdsForSessionPlan(s, sessionPlan).orEmpty()
            return TrackerMapGroupBoundsResolver.resolve(
                TrackerMapGroupBoundsInput(
                    visibleTrackerIds = visibleTrackerIds,
                    liveActiveFitEnabled = s.liveActiveFitEnabled,
                    fitOnlyActiveTrackers = trackerSettingsRepository.getSettings().groupModeFitOnlyActiveTrackers,
                    trailsByTracker = snapshot.renderTrailsByTracker,
                    remoteLastPoints = snapshot.acceptedRemoteLastPoints,
                    acceptedRemoteTrackerIds = sessionPlan.acceptedRemoteTrackerIds,
                    trackers = trackerManagementStateStore.trackers.value,
                    nowMs = nowMs,
                ),
            )
                ?: TrackerMapStateTransforms.trailBounds(snapshot.singleTrail)
                ?: singlePointBoundsFromRuntime(s.runtime)
        }
        val sessionPlan = snapshot.plan
        return TrackerMapStateTransforms.trailBounds(snapshot.singleTrail)
            ?: singlePointBoundsFromRuntime(s.runtime, sessionPlan)
    }

    private fun singlePointBoundsFromRuntime(
        runtime: TrackingRuntimeSnapshot,
        sessionPlan: TrackerMapStreamingPlan? = null,
    ): LatLngBounds? {
        if (sessionPlan != null &&
            sessionPlan.mode == TrackerMapDisplayMode.SINGLE_SESSION &&
            sessionPlan.selectedTrackerId.isNotEmpty() &&
            sessionPlan.displayedTrackerId != sessionPlan.selectedTrackerId
        ) {
            return null
        }
        val lat = runtime.lastTrackedLatitude ?: return null
        val lon = runtime.lastTrackedLongitude ?: return null
        return LatLngBounds.from(lat, lon, lat, lon)
    }

    private fun buildFallbackAccuracyByTrackerId(
        state: TrackerMapUiState,
        sessionPlan: TrackerMapStreamingPlan,
    ): Map<String, Float> {
        val fallbackByTrackerId = mutableMapOf<String, Float>()
        trackerManagementStateStore.trackers.value.forEach { tracker ->
            val trackerId = tracker.id.trim()
            if (trackerId.isEmpty()) return@forEach
            extractTrackerLatestAccuracyMeters(tracker)?.toFinitePositiveOrNull()?.let { accuracy ->
                fallbackByTrackerId[trackerId] = accuracy
            }
        }
        val selectedTrackerId = state.runtime.selectedTrackerId.trim()
        state.runtime.lastAccuracyMeters.toFinitePositiveOrNull()?.let { runtimeAccuracy ->
            if (selectedTrackerId.isNotEmpty() && selectedTrackerId in sessionPlan.localOverlayTrackerIds) {
                fallbackByTrackerId[selectedTrackerId] = runtimeAccuracy
            }
        }
        return fallbackByTrackerId
    }

    private fun resolveVisibleAccuracyTrackerIds(
        state: TrackerMapUiState,
        effectiveDisplayedId: String
    ): Set<String> {
        return buildSet {
            val displayedId = effectiveDisplayedId.trim()
            if (displayedId.isNotEmpty()) add(displayedId)
            state.allQueueTrailsByTracker.keys
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach(::add)
            state.remoteLastPoints.keys
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach(::add)
        }
    }

    private fun Float?.toFinitePositiveOrNull(): Float? {
        return this?.takeIf { it.isFinite() && it > 0f }
    }

    private fun extractTrackerLatestAccuracyMeters(tracker: Tracker): Float? {
        val accuracyRaw = tracker.point_params?.lastOrNull()?.get("acc") ?: return null
        return when (accuracyRaw) {
            is Number -> accuracyRaw.toFloat()
            is String -> accuracyRaw.toFloatOrNull()
            else -> null
        }
    }

    private fun refreshStreamTargets() {
        val state = _uiState.value
        val groupSelection = resolveGroupModeSelection(state)
        val visibleRosterTrackerIds = visibleMapRosterTrackerIds()
        val plan = projectSession(
            state = state,
            groupSelection = groupSelection,
            visibleRosterTrackerIds = visibleRosterTrackerIds,
        )
        val seed = TrackerMapReloadSeedPolicy.streamSeed(
            TrackerMapStreamSeedInput(
                mode = plan.mode,
                runtimeRunning = state.runtime.localRecordingActive,
                selectedTrackerId = plan.selectedTrackerId,
                displayedTrackerId = plan.displayedTrackerId,
                rosterTrackerIds = plan.visibleRosterTrackerIds,
                groupSelection = groupSelection
            )
        )
        val seedChanged = seed != lastStreamTargetsSeed
        val previousStreamTargetIds = state.streamTargetIds
        val nextStreamTargetIds = plan.remoteSubscriptionIds
        val shouldLoadHistoryForStreamingStart = seedChanged &&
            nextStreamTargetIds.isNotEmpty() &&
            nextStreamTargetIds != previousStreamTargetIds
        lastStreamTargetsSeed = seed
        val autoSelectionLockId = TrackerMapAutoLockPolicy.resolveAutoSelectionLockForSingleStream(
            mode = state.mode,
            previousTargets = previousStreamTargetIds,
            nextTargets = nextStreamTargetIds,
            displayedTrackerId = plan.displayedTrackerId,
        )
        _uiState.update { cur ->
            val baseNext = cur.copy(
                streamTargetIds = nextStreamTargetIds,
                remoteLastPoints = filterRemoteLastPointsForAcceptedIds(
                    remoteLastPoints = cur.remoteLastPoints,
                    acceptedRemoteTrackerIds = plan.acceptedRemoteTrackerIds,
                ),
                currentGroupId = if (state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
                    plan.resolvedGroupId
                } else {
                    cur.currentGroupId
                },
                groupModeOptions = if (state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
                    resolveGroupModeOptions()
                } else {
                    emptyList()
                },
            )
            val nextState = if (autoSelectionLockId != null) {
                baseNext.withAllMapLocksDisabled().copy(selectionLockTrackerId = autoSelectionLockId)
            } else {
                baseNext
            }
            if (nextState == cur) cur else nextState
        }
        if (shouldLoadHistoryForStreamingStart) {
            requestRuntimeTrailReload(TrackerMapTrailReloadReason.StreamingStart)
        }
    }

    private fun projectSession(
        state: TrackerMapUiState,
        groupSelection: TrackerMapGroupModeSelection = resolveGroupModeSelection(state),
        visibleRosterTrackerIds: Set<String> = visibleMapRosterTrackerIds(),
    ): TrackerMapStreamingPlan {
        return TrackerMapSessionProjector.project(
            TrackerMapSessionIntent(
                mode = state.mode,
                runtime = state.runtime,
                displayedTrackerId = state.displayedTrackerId,
                displayedTrackerName = state.displayedTrackerName,
                rosterTrackerIds = visibleRosterTrackerIds,
                groupSelection = groupSelection,
                activeStreamedTrackerIds = state.activeStreamedTrackerIds,
            )
        )
    }

    private suspend fun reloadTrailFromDatabase(reason: TrackerMapTrailReloadReason) {
        trailReloadMutex.withLock {
            reloadTrailFromDatabaseLocked(reason)
        }
    }

    private suspend fun reloadTrailFromDatabaseLocked(reason: TrackerMapTrailReloadReason) {
        val state = _uiState.value
        GeoVaultCaptureLog.i(
            TAG,
            "map_update vm_reload_start reason=$reason mode=${state.mode} displayed=${state.displayedTrackerId.trim()} " +
                "selected=${state.runtime.selectedTrackerId.trim()} localActive=${state.runtime.localRecordingActive} " +
                "trail=${state.trail.trailSummary()} multi=${state.allQueueTrailsByTracker.mapSizes()} " +
                "barriers=${clearedHistoryTrackerIds.sorted()}"
        )
        val groupSelection = resolveGroupModeSelection(state)
        val rosterTrackerIds = visibleMapRosterTrackerIds()
        val sessionPlan = projectSession(
            state = state,
            groupSelection = groupSelection,
            visibleRosterTrackerIds = rosterTrackerIds,
        )
        val activeTrackerId = sessionPlan.displayedTrackerId
        val guardInput = TrailReloadGuardInput(
            force = reason.allowServerHistoryFetch,
            mode = state.mode,
            trailSize = state.trail.size,
            runtimeRunning = state.runtime.localRecordingActive,
            displayedTrackerId = activeTrackerId,
            trailReloadPlan = sessionPlan.trailReloadPlan,
        )
        if (!TrackerMapTrailReloadGuardPolicy.shouldProceed(guardInput)) {
            GeoVaultCaptureLog.d(
                TAG,
                "map_update vm_reload_guard_skip reason=$reason mode=${state.mode} trailSize=${state.trail.size} " +
                    "runtimeRunning=${state.runtime.localRecordingActive} source=${sessionPlan.trailReloadPlan.source}"
            )
            return
        }
        // PRELOAD (early): seed an empty single-tracker trail from the in-memory cache as soon as
        // we know which tracker is displayed, BEFORE the allowsSource gate. Cosmetic refresh
        // reasons (MetadataMapRefresh / GenericMapRefresh) deliberately don't fetch from the
        // server, but they DO fire when the trackers list finally lands from the management
        // store — which is exactly when the cached geometry first becomes available. Skipping
        // preload on those reasons leaves the selected tracker's trail blank until a different
        // server-fetching reason fires (often never, on a quiet launch). The preload itself is
        // a pure in-memory copy, so it's safe and cheap to run for every reload.
        val preloadedTrail = preloadedSingleTrackerTrailFromCacheOrNull(
            mode = state.mode,
            activeTrackerId = activeTrackerId
        )
        if (preloadedTrail != null) {
            GeoVaultCaptureLog.d(
                TAG,
                "map_update vm_reload_preload tracker=$activeTrackerId points=${preloadedTrail.trailSummary()} reason=$reason"
            )
            _uiState.update { latest ->
                val restored = TrackerMapLocalTrailRestorePolicy.restore(
                    localHistoryTrail = preloadedTrail,
                    currentTrail = latest.trail,
                    trackerId = activeTrackerId,
                    trailPointLimit = TRAIL_POINT_LIMIT,
                )
                if (restored.changed) {
                    latest.copy(
                        trail = restored.trail,
                        allQueueTrailsByTracker = emptyMap(),
                    )
                } else {
                    latest
                }
            }
        }
        if (!reason.allowsSource(sessionPlan.trailReloadPlan.source)) {
            GeoVaultCaptureLog.d(
                TAG,
                "map_update vm_reload_skip_source reason=$reason source=${sessionPlan.trailReloadPlan.source} plan=$sessionPlan"
            )
            return
        }
        // RE-FIT AFTER FETCH: every reload that legitimately hit the server can move state.trail
        // arbitrarily far from whatever the camera is currently framing (process death + resume,
        // launch with a stale runtime GPS coord, switching to a different selected tracker, etc).
        // Without this flag the fit-after-reload path only triggers from explicit map context
        // change handlers — leaving the camera frozen on stale bounds even though the trail data
        // it was sized to is no longer in state. Treat any server-fetching reload as the caller
        // having implicitly asked the camera to re-fit when the new data lands.
        if (reason.allowServerHistoryFetch) {
            pendingFitAfterReload = true
        }
        val seed = TrackerMapReloadSeedPolicy.trailSeed(
            TrackerMapTrailSeedInput(
                mode = state.mode,
                runtimeRunning = state.runtime.localRecordingActive,
                activeTrackerId = sessionPlan.displayedTrackerId,
                rosterTrackerIds = rosterTrackerIds,
                groupSelection = groupSelection,
                renderMetadataSignature = state.renderMetadataSignature,
            )
        )
        if (!reason.allowServerHistoryFetch && lastTrailLoadSeed == seed) {
            GeoVaultCaptureLog.v(TAG, "map_update vm_reload_seed_skip reason=$reason seed=$seed")
            return
        }
        lastTrailLoadSeed = seed
        val planSourceState = _uiState.value
        val plan = projectSession(
            state = planSourceState,
            groupSelection = groupSelection,
            visibleRosterTrackerIds = rosterTrackerIds,
        ).trailReloadPlan
        GeoVaultCaptureLog.i(
            TAG,
            "map_update vm_reload_plan reason=$reason source=${plan.source} active=${plan.activeTrackerId} " +
                "single=${plan.singleTrackerId} trackers=${plan.trackerIds.sorted()} overlay=${plan.overlayTrackerId} seed=$seed"
        )
        val existingTrailMinTimeMs = planSourceState.trail.minOfOrNull { it.time }
        val existingMultiMinTimes = planSourceState.allQueueTrailsByTracker
            .mapValues { (_, pts) -> pts.minOfOrNull { it.time } }
            .filterValues { it != null }
            .mapValues { it.value!! }
        // SINGLE_SERVER + LOCAL OVERLAY: when the displayed tracker is the locally-recorded one,
        // server geometry alone can lag the live recording (uploads are async). The loader pairs
        // the server fetch with the local DB queue (returned in queueOverlaysByTracker) so the
        // merge has authoritative recent fixes to splice on top of server history.
        // MULTI_SERVER mirrors this: server geometry is loaded for every group/roster member and
        // returned untouched in serverTrails; the locally-recorded tracker's queue rows arrive in
        // queueOverlaysByTracker and are spliced as live-overlay candidates by the merge. They
        // never replace the server entry, so the multi view always retains real history for every
        // member — including the recording user.
        val loaded = TrackerMapTrailLoader.load(
            plan = plan,
            existingTrailMinTimeMs = existingTrailMinTimeMs,
            existingMultiMinTimes = existingMultiMinTimes,
            ops = trailLoaderOps,
        )
        GeoVaultCaptureLog.i(
            TAG,
            "map_update vm_reload_loaded reason=$reason source=${plan.source} " +
                "single=${loaded.singleTrailSeed.trailSummary()} server=${loaded.serverTrails.mapSizes()} " +
                "queueOverlays=${loaded.queueOverlaysByTracker.mapSizes()} authoritative=${loaded.authoritativeServerTrackerIds.sorted()}"
        )
        // RACE-FREE COMMIT: re-merge against the LATEST live trail at write time. The IO above
        // (loadQueueTrail / loadTrailsForTrackerIds / loadSingleTrackerTrailFromServer) suspends,
        // and the bus collector is free to append fresh fixes via handleTrackPointEvent during
        // that window. Snapshotting _uiState.value before the merge and writing it back via
        // .copy(...) silently overwrites those bus updates, causing the rendered marker to
        // regress one or two fixes (the "double-back while walking" symptom). _uiState.update
        // re-applies the merge against the latest state, preserving any newly-arrived live points.
        //
        // STALE-MODE GUARD: re-check the trail seed inside the atomic update so a context flip
        // (mode/displayed change) that lands between IO completion and the commit cannot apply a
        // wrong-mode result. The check inside the update closes the race window that an earlier
        // pre-commit check would still leave open.
        var mergeCommitted: MergedTrailResult? = null
        _uiState.update { latest ->
            if (trailSeedForState(latest) != seed) {
                return@update latest
            }
            // SESSION-SAFE OVERLAY: only honor active-session overlay points when the local
            // tracker is currently recording. The runtime carries the active session start;
            // overlay points stamped with a different non-null session start are stale and
            // would otherwise paint a cross-session connector ("spike") on the rendered line.
            val activeLocalTrackerId = latest.runtime.locallyRecordedTrackerId.trim()
            val activeSessionStart = latest.runtime.sessionStartTimeMs
                .takeIf { it > 0L && latest.runtime.localRecordingActive }
            val activeSessionStartByTracker: Map<String, Long> = if (
                activeSessionStart != null && activeLocalTrackerId.isNotEmpty()
            ) {
                mapOf(activeLocalTrackerId to activeSessionStart)
            } else {
                emptyMap()
            }
            val commit = TrackerMapTrailCommitPolicy.resolve(
                TrackerMapTrailCommitInput(
                    reason = reason,
                    plan = plan,
                    loaded = loaded,
                    latestState = latest,
                    trailPointLimit = TRAIL_POINT_LIMIT,
                    activeSessionStartByTracker = activeSessionStartByTracker,
                    clearedHistoryTrackerIds = clearedHistoryTrackerIds,
                )
            )
            mergeCommitted = MergedTrailResult(commit.trail, commit.multiTrails)
            GeoVaultCaptureLog.i(
                TAG,
                "map_update vm_reload_commit reason=$reason source=${plan.source} " +
                    "trail=${commit.trail.trailSummary()} multi=${commit.multiTrails.mapSizes()} " +
                    "barriers=${clearedHistoryTrackerIds.sorted()}"
            )
            latest.copy(
                trail = commit.trail,
                allQueueTrailsByTracker = commit.multiTrails,
                currentGroupId = if (latest.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
                    plan.resolvedGroupId
                } else {
                    latest.currentGroupId
                },
                groupModeOptions = if (latest.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
                    resolveGroupModeOptions()
                } else {
                    emptyList()
                },
            )
        }
        val finalMerge = mergeCommitted ?: run {
            // The update bailed because the seed flipped while IO was in flight. Reset the
            // cached seed so the next reload is not suppressed by lastTrailLoadSeed == seed.
            lastTrailLoadSeed = null
            return
        }
        if (reason == TrackerMapTrailReloadReason.HistoryCleared &&
            loaded.authoritativeServerTrackerIds.isNotEmpty()
        ) {
            clearedHistoryTrackerIds.removeAll(loaded.authoritativeServerTrackerIds)
            GeoVaultCaptureLog.i(
                TAG,
                "map_update vm_history_clear_barrier_released authoritative=${loaded.authoritativeServerTrackerIds.sorted()} " +
                    "remaining=${clearedHistoryTrackerIds.sorted()}"
            )
        }
        if (pendingFitAfterReload &&
            (finalMerge.trail.isNotEmpty() || finalMerge.multiTrails.isNotEmpty())
        ) {
            pendingFitAfterReload = false
            // INSTANT after server-fetching reload: the InitialFit directive (or a prior
            // user-driven fit) has already framed the camera on the locally-preloaded
            // bounds. The server response typically nudges those bounds by a small delta;
            // animating that delta produces a visible jolt at first map open. moveCamera
            // snaps to the final framing in one frame, which is the right semantics for a
            // re-fit the user did not initiate.
            requestFitTrail(TrackerMapFitTrailMode.Instant)
        }
    }

    private data class MergedTrailResult(
        val trail: List<QueuedLocation>,
        val multiTrails: Map<String, List<QueuedLocation>>,
    )

    private fun requestRuntimeTrailReload(reason: TrackerMapTrailReloadReason) {
        if (runtimeTrailReloadJob?.isActive == true) {
            runtimeTrailReloadPendingReason = runtimeTrailReloadPendingReason.mergedWith(reason)
            return
        }
        runtimeTrailReloadJob = viewModelScope.launch {
            var nextReason: TrackerMapTrailReloadReason? = reason
            while (nextReason != null) {
                val current = nextReason
                runtimeTrailReloadPendingReason = null
                reloadTrailFromDatabase(current)
                nextReason = runtimeTrailReloadPendingReason
            }
        }
    }

    private fun setOfNotBlank(value: String?): Set<String> {
        val normalized = value?.trim().orEmpty()
        return normalized.takeIf { it.isNotEmpty() }?.let(::setOf).orEmpty()
    }

    private fun TrackerMapTrailReloadReason.allowsSource(source: TrackerMapTrailSource): Boolean {
        return when {
            allowServerHistoryFetch -> true
            allowMultiServerHistoryFetch && source == TrackerMapTrailSource.MULTI_SERVER -> true
            source == TrackerMapTrailSource.SINGLE_QUEUE -> true
            else -> false
        }
    }

    private suspend fun loadQueueTrail(trackerId: String): List<QueuedLocation> {
        val normalizedTrackerId = trackerId.trim()
        if (normalizedTrackerId.isEmpty()) return emptyList()
        // Load up to QUEUE_TRAIL_FETCH_LIMIT (mirrors TrackingService.MAX_QUEUE_SIZE) and then
        // apply session-aware decimation. The previous version asked the DAO for exactly
        // TRAIL_POINT_LIMIT rows ordered by time DESC, which silently dropped the OLDEST points
        // when both sessions exceeded the cap — exactly the bug the recent_data_window=session
        // filter is supposed to prevent.
        val recent = withContext(Dispatchers.IO) {
            dao.getRecentChronologicalForTracker(normalizedTrackerId, QUEUE_TRAIL_FETCH_LIMIT)
        }
        return TrackerMapTrailDecimationPolicy.fitToCount(recent, TRAIL_POINT_LIMIT)
    }

    private suspend fun loadSingleTrackerTrailFromServer(
        trackerId: String,
        existingTrailMinTimeMs: Long?,
    ): TrackerMapServerTrailResult {
        return TrackerMapTrailDataCoordinator.loadSingleTrackerTrail(
            trackerId = trackerId,
            existingTrailMinTimeMs = existingTrailMinTimeMs,
            loadTrackerGeometry = { id ->
                sessionRequestDeduper.loadOnce("single:geometry:$id") {
                    geometryLoadingTracker.track { trackerManagementRepository.loadTrackerGeometry(id) }
                }
            },
            loadQueueTrail = { loadQueueTrail(trackerId) },
            mapCoordinatesToTrail = { id, merged, pointParams, minTime ->
                mapCoordinatesToTrail(id, merged, pointParams, minTime)
            }
        )
    }

    private suspend fun loadTrailsForTrackerIds(
        trackerIds: Collection<String>,
        existingTrailMinTimeMsByTracker: Map<String, Long>,
    ): TrackerMapServerTrailResult {
        return TrackerMapTrailDataCoordinator.loadTrailsForTrackerIds(
            trackerIds = trackerIds,
            existingTrailMinTimeMsByTracker = existingTrailMinTimeMsByTracker,
            loadTrackersGeometry = { ids ->
                val normalizedIds = ids.map { it.trim() }.filter { it.isNotEmpty() }.sorted()
                val key = "multi:geometry:${normalizedIds.joinToString(",")}"
                sessionRequestDeduper.loadOnce(key) {
                    geometryLoadingTracker.track { trackerManagementRepository.loadTrackersGeometry(ids) }
                }
            },
            loadQueueTrail = { id -> loadQueueTrail(id) },
            mapCoordinatesToTrail = { id, merged, pointParams, minTime ->
                mapCoordinatesToTrail(id, merged, pointParams, minTime)
            }
        )
    }

    private fun resolveGroupModeSelection(state: TrackerMapUiState): TrackerMapGroupModeSelection {
        if (state.mode != TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
            return TrackerMapGroupModeSelection(groupId = null, trackerIds = emptySet())
        }
        val visibility = trackerManagementStateStore.mapVisibility.value
        val hiddenGroupIds = visibility?.hidden_group_ids.orEmpty().toSet()
        val hiddenTrackIds = visibility?.hidden_track_ids.orEmpty().toSet()
        val hiddenOwnerTrackerIds = HiddenMapItemsPolicy.hiddenOwnerTrackerIds(trackerManagementStateStore.trackers.value)
        val preferredTrackerId = TrackerMapDisplayIds.effectiveDisplayedTrackerId(state).ifBlank { state.runtime.selectedTrackerId }
        return TrackerMapGroupModePolicy.resolveSelection(
            groups = trackerManagementStateStore.groups.value,
            hiddenGroupIds = hiddenGroupIds,
            hiddenTrackIds = hiddenTrackIds,
            hiddenOwnerTrackerIds = hiddenOwnerTrackerIds,
            preferredGroupId = state.currentGroupId,
            preferredTrackerId = preferredTrackerId
        )
    }

    private fun visibleMapRosterTrackerIds(): Set<String> {
        val trackers = trackerManagementStateStore.trackers.value
        return HiddenMapItemsPolicy.visibleTrackerIdsForMap(
            rosterTrackerIds = trackers.map { it.id },
            mapVisibility = trackerManagementStateStore.mapVisibility.value,
            trackers = trackers,
        )
    }

    private fun resolveGroupModeOptions(): List<TrackerMapGroupModeOption> {
        val visibility = trackerManagementStateStore.mapVisibility.value
        val hiddenGroupIds = visibility?.hidden_group_ids.orEmpty().toSet()
        val hiddenTrackIds = visibility?.hidden_track_ids.orEmpty().toSet()
        val hiddenOwnerTrackerIds = HiddenMapItemsPolicy.hiddenOwnerTrackerIds(trackerManagementStateStore.trackers.value)
        return TrackerMapGroupModePolicy.resolveEligibleGroups(
            groups = trackerManagementStateStore.groups.value,
            hiddenGroupIds = hiddenGroupIds,
            hiddenTrackIds = hiddenTrackIds,
            hiddenOwnerTrackerIds = hiddenOwnerTrackerIds
        )
    }

    private fun mapCoordinatesToTrail(
        trackerId: String,
        coordinates: List<List<Double>>,
        pointParams: List<Map<String, Any?>>? = null,
        existingTrailMinTimeMs: Long? = null,
    ): List<QueuedLocation> = TrackerMapTrailMaterializationPolicy.materialize(
        trackerId = trackerId,
        coordinates = coordinates,
        pointParams = pointParams,
        existingTrailMinTimeMs = existingTrailMinTimeMs,
        trailPointLimit = TRAIL_POINT_LIMIT,
    )

    /**
     * Seed `_uiState.trail` from the local Room queue at ViewModel construction. Runs
     * concurrently with the rest of `init`; the goal is for this to land before the
     * Compose layer attaches its `_uiState.collect` listener so the very first render
     * package the map sees already has a trail to fit the camera to.
     *
     * Race semantics: if any other code path populates the trail first (a server fetch,
     * a track point arriving, a reload coordinator preload), we leave it alone. If the
     * trail is still empty when we land, we populate it AND seed `displayedTrackerId`
     * from the persisted selection so downstream camera/render logic has a target.
     */
    private suspend fun seedInitialTrailFromLocalQueue() {
        val context = getApplication<Application>()
        val selectedId = SelectedTrackerPrefs.selectedTrackerId(context).trim()
        if (selectedId.isEmpty()) return
        val queueTrail = loadQueueTrail(selectedId)
        if (queueTrail.isEmpty()) return
        _uiState.update { latest ->
            val displayedNow = latest.displayedTrackerId.trim()
            if (displayedNow.isNotEmpty() && displayedNow != selectedId) return@update latest
            val restored = TrackerMapLocalTrailRestorePolicy.restore(
                localHistoryTrail = queueTrail,
                currentTrail = latest.trail,
                trackerId = selectedId,
                trailPointLimit = TRAIL_POINT_LIMIT,
            )
            if (!restored.changed) return@update latest
            val displayedId = if (latest.displayedTrackerId.isBlank()) {
                selectedId
            } else {
                latest.displayedTrackerId
            }
            val displayedName = if (latest.displayedTrackerName.isBlank()) {
                SelectedTrackerPrefs.selectedTrackerName(context)
            } else {
                latest.displayedTrackerName
            }
            latest.copy(
                trail = restored.trail,
                displayedTrackerId = displayedId,
                displayedTrackerName = displayedName,
            )
        }
    }

    /**
     * Seed the single-tracker trail from the most recently available local source so the
     * map has SOMETHING to fit to before the (slow) server geometry fetch returns.
     *
     * Source priority:
     *  1. In-memory `TrackerManagementStateStore` cache. Populated by previous geometry
     *     fetches in this process; effectively always empty on a fresh launch because
     *     `GET /trackers/` returns metadata-only (no geometry).
     *  2. Local Room queue (`loadQueueTrail`). Persists across process death, so on every
     *     launch after the first recording session this provides recent fixes for the
     *     locally-recorded tracker without any network round-trip. The merge policy
     *     drops these once the server response arrives (queue rows are not tagged as
     *     live overlay), so they cleanly hand off without leaving stale data behind.
     *
     * Returning null means "no local data available, let the server fetch handle it."
     * The two-source pattern eliminates the visible 0,0 flash that occurred when the
     * cache was empty (every cold launch) and the trail stayed empty through the
     * geometry fetch window.
     */
    private suspend fun preloadedSingleTrackerTrailFromCacheOrNull(
        mode: TrackerMapDisplayMode,
        activeTrackerId: String
    ): List<QueuedLocation>? {
        if (mode != TrackerMapDisplayMode.SINGLE_SESSION) return null
        val trackerId = activeTrackerId.trim()
        if (trackerId.isEmpty()) return null
        if (trackerId in clearedHistoryTrackerIds) return null
        val cachedTracker = trackerManagementStateStore.trackers.value
            .firstOrNull { it.id == trackerId }
        val cachedGeometry = cachedTracker?.geometry?.coordinates.orEmpty()
        if (cachedGeometry.isNotEmpty()) {
            // Pass point_params alongside the coordinates so each preloaded point carries its
            // starttimestamp. Without this, the recent_data_window=session filter cannot attribute
            // previous-session points and they vanish from the trail until the (later) server
            // fetch lands — visible to the user as "missing history" after closing & reopening
            // the app mid-session.
            val trail = mapCoordinatesToTrail(
                trackerId,
                cachedGeometry,
                pointParams = cachedTracker?.point_params,
            )
            if (trail.isNotEmpty()) {
                return TrackerMapLocalTrailRestorePolicy.restore(
                    localHistoryTrail = trail,
                    currentTrail = _uiState.value.trail,
                    trackerId = trackerId,
                    trailPointLimit = TRAIL_POINT_LIMIT,
                ).trail
            }
        }
        val queueTrail = loadQueueTrail(trackerId)
        if (queueTrail.isEmpty()) return null
        return TrackerMapLocalTrailRestorePolicy.restore(
            localHistoryTrail = queueTrail,
            currentTrail = _uiState.value.trail,
            trackerId = trackerId,
            trailPointLimit = TRAIL_POINT_LIMIT,
        ).trail
    }

    private fun handleTrackPointEvent(point: TrackPointEvent) {
        val nowMs = System.currentTimeMillis()
        val snapshot = buildCurrentSessionSnapshot(nowMs)
        GeoVaultCaptureLog.d(
            TAG,
            "map_update vm_point_reduce_start source=${point.source} track=${point.trackId.trim()} " +
                "ts=${point.timestampMs} mode=${snapshot.mode} displayed=${snapshot.plan.displayedTrackerId} " +
                "acceptedRemote=${snapshot.plan.acceptedRemoteTrackerIds.sorted()} " +
                "localOverlay=${snapshot.plan.localOverlayTrackerIds.sorted()} " +
                "singleBefore=${snapshot.singleTrail.trailSummary()} multiBefore=${snapshot.renderTrailsByTracker.mapSizes()}"
        )
        val reduction = TrackerMapSessionEngine.reducePoint(
            TrackerMapSessionPointInput(
                snapshot = snapshot,
                point = point,
                trailPointLimit = TRAIL_POINT_LIMIT,
                recentDataWindowByTracker = currentRecentDataWindowByTracker(),
                currentSessionStartByTracker = currentSessionStartByTracker(snapshot.uiState),
                visibleTrackerIds = visibleTrackerIdsForSessionPlan(snapshot.uiState, snapshot.plan),
                nowMs = nowMs,
            )
        )
        GeoVaultCaptureLog.d(
            TAG,
            "map_update vm_point_reduce_result source=${point.source} track=${point.trackId.trim()} " +
                "accepted=${reduction.acceptedBySourcePolicy} update=${reduction.shouldUpdate} " +
                "singleAfter=${reduction.nextSnapshot.singleTrail.trailSummary()} " +
                "multiAfter=${reduction.nextSnapshot.renderTrailsByTracker.mapSizes()} " +
                "remoteAfter=${reduction.nextSnapshot.acceptedRemoteLastPoints.keys.sorted()}"
        )
        if (reduction.shouldUpdate) {
            val nextState = stateWithRefreshedSelectionCard(
                state = reduction.nextSnapshot.uiState,
                changedTrackerId = point.trackId,
            )
            _uiState.value = nextState
            if (nextState.liveActiveFitEnabled) {
                requestFitTrail()
            }
        }
    }

    private fun stateWithRefreshedSelectionCard(
        state: TrackerMapUiState,
        changedTrackerId: String,
    ): TrackerMapUiState {
        val selection = state.selectedMapTracker ?: return state
        if (!state.isBottomCardVisible || selection.trackerId != changedTrackerId.trim()) return state
        val refreshed = buildSelectionCard(buildSessionSnapshotForState(state), selection.trackerId) ?: return state
        return state.copy(selectedMapTracker = refreshed)
    }

    fun acceptedRemoteTrackerIdsForCurrentSession(): Set<String> {
        return buildCurrentSessionSnapshot().plan.acceptedRemoteTrackerIds
    }

    private fun reconcileStreaming(
        state: TrackerMapUiState,
        streamRuntime: LiveStreamRuntimeSnapshot = LiveStreamRuntimeStateStore.state.value,
    ) {
        val plan = projectSession(state)
        val decisionState = state.copy(streamTargetIds = plan.remoteSubscriptionIds)
        streamingReconciler.reconcile(
            decisionState,
            plan.displayedTrackerId,
            plan.displayedTrackerName,
            streamRuntime,
        )
    }

    private fun effectiveDisplayedTrackerName(state: TrackerMapUiState): String {
        return state.displayedTrackerName.trim().ifBlank { state.runtime.selectedTrackerName.trim() }
    }

    private fun trailSeedForState(state: TrackerMapUiState): String {
        val groupSelection = resolveGroupModeSelection(state)
        val rosterIds = visibleMapRosterTrackerIds()
        val plan = projectSession(
            state = state,
            groupSelection = groupSelection,
            visibleRosterTrackerIds = rosterIds,
        )
        return TrackerMapReloadSeedPolicy.trailSeed(
            TrackerMapTrailSeedInput(
                mode = state.mode,
                runtimeRunning = state.runtime.localRecordingActive,
                activeTrackerId = plan.displayedTrackerId,
                rosterTrackerIds = rosterIds,
                groupSelection = groupSelection,
                renderMetadataSignature = state.renderMetadataSignature,
            )
        )
    }

    private fun Tracker.settingString(key: String): String? {
        val raw = settings?.get(key) ?: return null
        return when (raw) {
            is String -> raw
            else -> raw.toString()
        }
    }

    private fun Tracker.settingBoolean(key: String): Boolean? {
        return when (val raw = settings?.get(key)) {
            is Boolean -> raw
            is String -> raw.toBooleanStrictOrNull()
            else -> null
        }
    }

    private fun setGeometryLoading(isLoading: Boolean) {
        val current = _uiState.value
        if (current.isGeometryLoading == isLoading) return
        GeoVaultCaptureLog.d(TAG, "map_update vm_geometry_loading from=${current.isGeometryLoading} to=$isLoading")
        _uiState.value = current.copy(isGeometryLoading = isLoading)
    }

    private fun List<QueuedLocation>.trailSummary(): String {
        val first = firstOrNull()
        val last = lastOrNull()
        return "count=$size first=${first?.time}:${first?.latitude},${first?.longitude}/${first?.prov}/${first?.startTimestampMs} " +
            "last=${last?.time}:${last?.latitude},${last?.longitude}/${last?.prov}/${last?.startTimestampMs}"
    }

    private fun Map<String, List<QueuedLocation>>.mapSizes(): String {
        if (isEmpty()) return "{}"
        return entries
            .sortedBy { it.key }
            .joinToString(prefix = "{", postfix = "}") { (trackerId, points) ->
                "$trackerId:${points.size}:${points.lastOrNull()?.time}:${points.lastOrNull()?.prov}"
            }
    }

    private fun LatLngBounds?.boundsSummary(): String {
        this ?: return "null"
        return "sw=${southWest.latitude},${southWest.longitude} ne=${northEast.latitude},${northEast.longitude}"
    }

    override fun onCleared() {
        pointEventChannel.close()
        fitTrailSignal.close()
        super.onCleared()
    }
}
