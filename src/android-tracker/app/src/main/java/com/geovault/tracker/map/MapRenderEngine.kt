package com.geovault.tracker.map

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.geovault.common.concurrent.GeoVaultStateStore
import com.geovault.common.coroutines.launchSupervisedCollector
import com.geovault.common.logging.CaptureLogThrottle
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.common.maps.core.GeoVaultMapPaddingDp
import com.geovault.common.maps.core.GeoVaultMapPaddingPolicy
import com.geovault.common.maps.core.geoVaultLatLngBoundsUnion
import com.geovault.common.maps.ui.camera.GeoVaultMapCameraDirectiveBus
import com.geovault.common.maps.ui.location.GeoVaultMapLocationSessionDecision
import com.geovault.common.maps.ui.location.GeoVaultMapLocationSessionInput
import com.geovault.common.maps.ui.location.GeoVaultMapLocationSessionPolicy
import com.geovault.common.ui.theme.GeoVaultColorHex
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.tracker.Tracker
import com.geovault.tracker.presentation.HiddenMapItemsPolicy
import com.geovault.tracker.presentation.TrackerMapAccuracyRenderModel
import com.geovault.tracker.presentation.LiveActiveFitInput
import com.geovault.tracker.presentation.LiveActiveFitVisibility
import com.geovault.tracker.presentation.TrackerMapChromeModel
import com.geovault.tracker.presentation.TrackerMapRecordingChrome
import com.geovault.tracker.presentation.TrackerMapCameraDirective
import com.geovault.tracker.presentation.TrackerMapCameraDirectiveInput
import com.geovault.tracker.presentation.TrackerMapCameraDirectiveResolution
import com.geovault.tracker.presentation.TrackerMapDisplayIds
import com.geovault.tracker.presentation.TrackerMapGpsAccuracyIndicatorUiModel
import com.geovault.tracker.presentation.TrackerMapIconIds
import com.geovault.tracker.presentation.TrackerMapLockFabBehavior
import com.geovault.tracker.presentation.TrackerMapLockFabInput
import com.geovault.tracker.presentation.TrackerMapDisplayMode
import com.geovault.tracker.presentation.TrackerMapFitTrailMode
import com.geovault.tracker.presentation.TrackerMapEffectiveSession
import com.geovault.tracker.presentation.TrackerMapEffectiveSessionInput
import com.geovault.tracker.presentation.TrackerMapFollowLockTarget
import com.geovault.tracker.presentation.TrackerMapGroupBoundsInput
import com.geovault.tracker.presentation.TrackerMapGroupBoundsResolution
import com.geovault.tracker.presentation.TrackerMapGroupBoundsResolver
import com.geovault.tracker.presentation.TrackerMapRenderCosmetics
import com.geovault.tracker.presentation.TrackerMapRenderPackage
import com.geovault.tracker.presentation.TrackerMapSessionBuildInput
import com.geovault.tracker.presentation.TrackerMapSessionSnapshot
import com.geovault.tracker.presentation.TrackerMapStreamingPlan
import com.geovault.tracker.presentation.TrackerTrackModel
import com.geovault.tracker.presentation.TrackerMapTopLeftChipMapper
import com.geovault.tracker.presentation.TrackerMapUiState
import com.geovault.tracker.presentation.TrackerMapUserLocationBlocker
import com.geovault.tracker.presentation.TrackerMapUserLocationDecision
import com.geovault.tracker.presentation.TrackerMapUserLocationInput
import com.geovault.tracker.presentation.TrackerMapViewModel
import com.geovault.tracker.positioning.TrackingRuntimeSnapshot
import com.geovault.tracker.streaming.StreamingConfig
import com.geovault.tracker.streaming.StreamingDiagnostics
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds

/**
 * Owns published render and the generation-stamped camera bus.
 */
internal class MapRenderEngine {
    private val cameraBus = GeoVaultMapCameraDirectiveBus<TrackerMapCameraDirective>(
        TrackerMapCameraDirective.None(),
    )
    private val renders = GeoVaultStateStore(TrackerMapRenderPackage())
    private val chromes = GeoVaultStateStore(TrackerMapChromeModel())
    val renderPackage: StateFlow<TrackerMapRenderPackage> = renders.state
    val chrome: StateFlow<TrackerMapChromeModel> = chromes.state
    val cameraDirective: StateFlow<TrackerMapCameraDirective> = cameraBus.directive
    val cameraGenerationFlow: StateFlow<Long> = cameraBus.generationFlow
    val cameraGeneration: Long get() = cameraBus.generation
    val userOwnsZoom: Boolean get() = cameraBus.userOwnsZoom
    private var followPuckLatitude: Double? = null
    private var followPuckLongitude: Double? = null
    private var gpsHomeAnchorLatitude: Double? = null
    private var gpsHomeAnchorLongitude: Double? = null
    private var lastCameraViewportSeed: String? = null
    private var locationSurface = TrackerMapUserLocationInput(
        isMapActive = false,
        hasLocationPermission = false,
        isMapReady = false,
        userLocationRequestedThisSession = false,
    )
    private var keepScreenOnWhileViewingMap: Boolean = false

    private var runtime: TrackerMapRuntime? = null
    private val chipMapper = TrackerMapTopLeftChipMapper()

    fun publish(pkg: TrackerMapRenderPackage) {
        renders.replace(pkg)
    }

    fun start(rt: TrackerMapRuntime) {
        runtime = rt
        keepScreenOnWhileViewingMap = rt.dependencies.trackerSettingsRepository.getSettings().keepScreenOnWhileViewingMap
        rt.ports.viewModelScope.launchSupervisedCollector(
            tag = "render-session-trail",
            flow = combine(rt.sessionEngine.document, rt.trailEngine.trail) { session, trail ->
                session to trail
            },
            retryDelayMs = StreamingConfig.collectorRestartDelayMs,
            onError = StreamingDiagnostics::logCollectorRestart,
        ) {
            publishRenderPackage()
        }
        rt.ports.viewModelScope.launchSupervisedCollector(
            tag = "render-resync",
            flow = combine(rt.stateHub.uiStateMutable, rt.dependencies.historyRepository.snapshots) { state, _ -> state },
            retryDelayMs = StreamingConfig.collectorRestartDelayMs,
            onError = StreamingDiagnostics::logCollectorRestart,
        ) {
            publishRenderPackage()
        }
        rt.ports.viewModelScope.launchSupervisedCollector(
            tag = "group-mode-fit-toggle",
            flow = rt.dependencies.trackerSettingsRepository.observeSettings()
                .map { it.groupModeFitOnlyActiveTrackers }
                .distinctUntilChanged()
                .drop(1),
            retryDelayMs = StreamingConfig.collectorRestartDelayMs,
            onError = StreamingDiagnostics::logCollectorRestart,
        ) {
            publishRenderPackage()
        }
        rt.ports.viewModelScope.launchSupervisedCollector(
            tag = "chrome-catalog",
            flow = rt.dependencies.catalogStateStore.state,
            retryDelayMs = StreamingConfig.collectorRestartDelayMs,
            onError = StreamingDiagnostics::logCollectorRestart,
        ) {
            publishChrome()
        }
        rt.ports.viewModelScope.launchSupervisedCollector(
            tag = "chrome-keep-screen",
            flow = rt.dependencies.trackerSettingsRepository.observeSettings()
                .map { it.keepScreenOnWhileViewingMap }
                .distinctUntilChanged(),
            retryDelayMs = StreamingConfig.collectorRestartDelayMs,
            onError = StreamingDiagnostics::logCollectorRestart,
        ) { enabled ->
            keepScreenOnWhileViewingMap = enabled
            publishChrome()
        }
    }

    internal fun refreshLocationSurfaceFromSession() {
        updateLocationSurface(locationSurface, force = true)
    }

    internal fun updateLocationSurface(input: TrackerMapUserLocationInput, force: Boolean = false) {
        if (!force && input == locationSurface) return
        locationSurface = input
        val rt = runtime
        val state = rt?.stateHub?.uiStateMutable?.value
        val puckRequested = rt?.sessionEngine?.document?.value?.surface?.liveGpsPuckRequested
            ?: input.userLocationRequestedThisSession
        val decision = MapRenderMath.evaluateUserLocation(
            input.copy(
                userLocationRequestedThisSession = puckRequested,
                displayedTrackerId = if (rt != null && state != null) {
                    rt.displayedTrackerId(state)
                } else {
                    input.displayedTrackerId
                },
                locallyRecordedTrackerId = rt?.recording()?.locallyRecordedTrackerId
                    ?: input.locallyRecordedTrackerId,
            )
        )
        if (!decision.shouldEnablePuck) {
            clearGpsCameraAnchors()
        }
        if (rt == null || state == null) return
        publishChrome()
    }

    private fun publishChrome() {
        val rt = runtime ?: return
        val state = rt.stateHub.uiStateMutable.value
        val trails = rt.trailEngine.trail.value
        val displayedTrackerId = rt.displayedTrackerId(state)
        val catalogSelectedTrackerId = rt.catalogSelectedTrackerId()
        val recording = rt.recording()
        val puckRequested = rt.sessionEngine.document.value.surface.liveGpsPuckRequested
        val userLocation = MapRenderMath.evaluateUserLocation(
            locationSurface.copy(
                userLocationRequestedThisSession = puckRequested,
                displayedTrackerId = displayedTrackerId,
                locallyRecordedTrackerId = recording.locallyRecordedTrackerId,
            )
        )
        val lockFab = MapRenderMath.resolveLockFab(
            TrackerMapLockFabInput(
                mode = state.mode,
                displayedTrackerId = displayedTrackerId,
                selectionLockTrackerId = state.selectionLockTrackerId,
                liveActiveFitEnabled = state.liveActiveFitEnabled,
                followLockEnabled = state.followLockEnabled,
            )
        )
        val selectionLock = lockFab as? TrackerMapLockFabBehavior.SelectionLock
        val liveActiveFit = MapRenderMath.resolveLiveActiveFitVisibility(
            LiveActiveFitInput(
                mode = state.mode,
                followLockArmed = MapRenderMath.resolveLiveActiveFitLockArmed(
                    singleTrackerLocked = selectionLock?.isLocked == true,
                ),
                liveActiveFitEnabled = state.liveActiveFitEnabled,
                hasTrailPoints = trails.hasTrailPoints,
                isSelectedDefaultTracker = selectionLock != null &&
                    selectionLock.displayedTrackerId == catalogSelectedTrackerId,
                hasMultipleTrackersOnMap = userLocation.shouldEnablePuck,
            )
        )
        val plan = rt.sessionEngine.resolvePlan(state)
        chromes.replace(
            TrackerMapChromeModel(
                chip = chipMapper.map(
                    state = state,
                    roster = rt.trackerRosterForMapChip(),
                    acceptedRemoteTrackerIds = plan.acceptedRemoteTrackerIds,
                    selectedTrackerId = catalogSelectedTrackerId,
                    selectedTrackerName = rt.catalogSelectedTrackerName(),
                    liveHeads = trails.remoteLastPoints,
                    recording = recording,
                ),
                lockFab = lockFab,
                showMyLocationFab = MapRenderMath.shouldShowMyLocationFab(
                    mode = state.mode,
                    displayedTrackerId = state.displayedTrackerId,
                    selectedTrackerId = catalogSelectedTrackerId,
                ),
                liveActiveFit = liveActiveFit,
                liveActiveFitEnabled = state.liveActiveFitEnabled,
                userLocation = userLocation,
                gpsAccuracy = MapRenderMath.resolveGpsAccuracyIndicator(recording),
                keepScreenOnWhileViewingMap = keepScreenOnWhileViewingMap,
                recording = TrackerMapRecordingChrome(
                    selectedTrackerId = catalogSelectedTrackerId,
                    locallyRecordedTrackerId = recording.locallyRecordedTrackerId,
                    localRecordingActive = recording.localRecordingActive,
                    lastTrackedLatitude = recording.lastTrackedLatitude,
                    lastTrackedLongitude = recording.lastTrackedLongitude,
                    lastTrackedTimestampMs = recording.lastTrackedTimestampMs,
                    lastAccuracyMeters = recording.lastAccuracyMeters,
                ),
                streamingStatus = state.streamingStatus,
                trailDegradedVisible = trails.hasDegradedTrails,
            )
        )
    }

    internal suspend fun publishRenderPackage() {
        val rt = runtime ?: return
        val nowMs = System.currentTimeMillis()
        val effectiveSession = rt.withTrailCommit {
            buildCurrentEffectiveSession(nowMs = nowMs)
        }
        val snapshot = effectiveSession.snapshot
        val nextRenderState = buildMapRenderState(snapshot)
        val nextBounds = trailBoundsOrNull(snapshot, nowMs)
        val nextSelectionLockPoint = rt.sessionEngine.selectionLockPointOrNull(snapshot)
        val renderSignature =
            "mode=${snapshot.mode}|displayed=${snapshot.plan.displayedTrackerId}|single=${snapshot.singleTrail.size}|" +
                "multi=${snapshot.renderTrailsByTracker.mapSizes()}|liveHead=${effectiveSession.liveHead}|" +
                "bounds=${nextBounds.boundsSummary()}|selectionLock=${rt.stateHub.uiStateMutable.value.selectionLockTrackerId.trim()}|" +
                "historyKeys=${rt.dependencies.historyRepository.snapshots.value.size}"
        if (CaptureLogThrottle.shouldLogOnChange("map_draw_package", renderSignature)) {
            GeoVaultCaptureLog.d(
                TrackerMapViewModel.TAG,
                "map_draw_package mode=${snapshot.mode} displayed=${snapshot.plan.displayedTrackerId} " +
                    "selected=${snapshot.plan.selectedTrackerId} single=${snapshot.singleTrail.trailSummary()} " +
                    "multi=${snapshot.renderTrailsByTracker.mapSizes()} remote=${snapshot.acceptedRemoteLastPoints.keys.sorted()} " +
                    "history_snapshot_keys=${rt.dependencies.historyRepository.snapshots.value.size} " +
                    "liveHead=${effectiveSession.liveHead} bounds=${nextBounds.boundsSummary()} " +
                    "selectionLock=${rt.stateHub.uiStateMutable.value.selectionLockTrackerId.trim()} selectionPoint=$nextSelectionLockPoint",
            )
        }
        val current = renderPackage.value
        val nextPackage = if (current.renderState == nextRenderState &&
            current.bounds == nextBounds &&
            current.selectionLockPoint == nextSelectionLockPoint &&
            current.liveHead == effectiveSession.liveHead
        ) {
            current
        } else {
            TrackerMapRenderPackage(
                renderState = nextRenderState,
                bounds = nextBounds,
                selectionLockPoint = nextSelectionLockPoint,
                liveHead = effectiveSession.liveHead,
                revision = current.revision + 1L,
            )
        }
        publish(nextPackage)
        rt.trailEngine.publish(snapshot)
        publishChrome()
        publishCameraDirective(
            state = snapshot.uiState,
            liveHead = effectiveSession.liveHead,
            bounds = nextBounds,
            selectionLockPoint = nextSelectionLockPoint,
        )
    }

    internal fun refreshFollowLockCamera() {
        val rt = runtime ?: return
        val snapshot = buildCurrentSessionSnapshot()
        val nextBounds = trailBoundsOrNull(snapshot, System.currentTimeMillis())
        publishCameraDirective(
            state = snapshot.uiState,
            liveHead = MapRenderMath.resolveLiveHead(snapshot),
            bounds = nextBounds,
            selectionLockPoint = rt.sessionEngine.selectionLockPointOrNull(snapshot),
        )
    }

    private fun publishCameraDirective(
        state: TrackerMapUiState,
        liveHead: Pair<Double, Double>?,
        bounds: LatLngBounds?,
        selectionLockPoint: Pair<Double, Double>?,
    ) {
        val rt = runtime ?: return
        maybeClearAnchorsOnViewportChange(state)
        val followTarget = TrackerMapFollowLockTarget.resolve(
            followLockEnabled = state.followLockEnabled,
            puckLatitude = followPuckLatitude,
            puckLongitude = followPuckLongitude,
            liveHead = liveHead,
        )
        val input = TrackerMapCameraDirectiveInput(
            followLockEnabled = state.followLockEnabled,
            gpsCollecting = rt.recording().gpsCollecting,
            followTargetLat = followTarget?.first,
            followTargetLon = followTarget?.second,
            selectionLockEnabled = state.selectionLockTrackerId.trim().isNotEmpty(),
            selectionLockLat = selectionLockPoint?.first,
            selectionLockLon = selectionLockPoint?.second,
            liveActiveFitEnabled = state.liveActiveFitEnabled,
            bounds = bounds,
            userOwnsZoom = cameraBus.userOwnsZoom,
        )
        if (CaptureLogThrottle.shouldLogOnChange("vm_camera_resolve", input.toString())) {
            GeoVaultCaptureLog.d(
                TrackerMapViewModel.TAG,
                "map_update vm_camera_resolve mode=${state.mode} follow=${state.followLockEnabled} " +
                    "gpsCollecting=${rt.recording().gpsCollecting} followTarget=$followTarget " +
                    "selectionLock=${state.selectionLockTrackerId.trim()} selectionPoint=$selectionLockPoint " +
                    "liveFit=${state.liveActiveFitEnabled} bounds=${bounds.boundsSummary()}",
            )
        }
        resolveFromLockState(input)
    }

    internal fun setFollowPuck(latitude: Double, longitude: Double) {
        followPuckLatitude = latitude
        followPuckLongitude = longitude
    }

    internal fun setGpsHomeAnchor(latitude: Double, longitude: Double) {
        gpsHomeAnchorLatitude = latitude
        gpsHomeAnchorLongitude = longitude
    }

    internal fun clearGpsCameraAnchors() {
        gpsHomeAnchorLatitude = null
        gpsHomeAnchorLongitude = null
        followPuckLatitude = null
        followPuckLongitude = null
    }

    private fun maybeClearAnchorsOnViewportChange(state: TrackerMapUiState) {
        val rt = runtime ?: return
        val displayed = rt.displayedTrackerId(state)
        val seed = "${state.mode}|${state.currentGroupId.trim()}|$displayed"
        if (lastCameraViewportSeed == null) {
            lastCameraViewportSeed = seed
            return
        }
        if (seed == lastCameraViewportSeed) return
        lastCameraViewportSeed = seed
        clearGpsCameraAnchors()
    }

    private fun unionBoundsWithAnchor(
        bounds: LatLngBounds,
        latitude: Double?,
        longitude: Double?,
    ): LatLngBounds {
        if (latitude == null || longitude == null) return bounds
        return geoVaultLatLngBoundsUnion(bounds, listOf(LatLng(latitude, longitude)))
    }

    private fun shouldUnionLivePuck(): Boolean {
        return MapRenderMath.evaluateUserLocation(locationSurface).shouldEnablePuck &&
            followPuckLatitude != null &&
            followPuckLongitude != null
    }

    internal fun followPuckLatitude(): Double? = followPuckLatitude

    internal fun followPuckLongitude(): Double? = followPuckLongitude

    internal fun onUserGestureStarted() {
        cameraBus.onUserGestureStarted()
    }

    internal fun onUserOwnedZoom() {
        cameraBus.onUserOwnedZoom()
    }

    internal fun resetLastResolution() {
        cameraBus.resetDedup()
    }

    internal fun resolveFromLockState(input: TrackerMapCameraDirectiveInput) {
        val resolution = MapRenderMath.resolveCameraDirective(input)
        val unioned = if (
            resolution.reason == TrackerMapCameraDirective.Reason.LiveActiveFit &&
            resolution.bounds != null &&
            shouldUnionLivePuck()
        ) {
            resolution.copy(
                bounds = unionBoundsWithAnchor(
                    resolution.bounds,
                    followPuckLatitude,
                    followPuckLongitude,
                ),
            )
        } else {
            resolution
        }
        cameraBus.publishIfUnchanged(unioned) { id, generation ->
            MapRenderMath.mintCameraDirective(unioned, id, generation)
        }
    }

    internal fun requestExplicitFit(bounds: LatLngBounds?, mode: TrackerMapFitTrailMode) {
        if (bounds == null) return
        val unioned = unionBoundsWithAnchor(bounds, gpsHomeAnchorLatitude, gpsHomeAnchorLongitude)
        cameraBus.publish { id, generation ->
            TrackerMapCameraDirective.FitBounds(
                bounds = unioned,
                mode = mode,
                reason = TrackerMapCameraDirective.Reason.ExplicitFit,
                id = id,
                generation = generation,
            )
        }
    }

    private fun buildCurrentEffectiveSession(nowMs: Long = System.currentTimeMillis()): TrackerMapEffectiveSession {
        val rt = runtime ?: return MapRenderMath.project(
            TrackerMapEffectiveSessionInput(
                state = TrackerMapUiState(),
                plan = rtFallbackPlan(),
                trailPointLimit = TrackerMapViewModel.TRAIL_POINT_LIMIT,
                visibleTrackerIds = null,
                nowMs = nowMs,
            )
        )
        return buildEffectiveSessionForState(rt.stateHub.uiStateMutable.value, nowMs)
    }

    internal fun buildCurrentSessionSnapshot(nowMs: Long = System.currentTimeMillis()): TrackerMapSessionSnapshot {
        return buildCurrentEffectiveSession(nowMs).snapshot
    }

    internal fun buildSessionSnapshotForState(
        state: TrackerMapUiState,
        nowMs: Long = System.currentTimeMillis(),
    ): TrackerMapSessionSnapshot {
        return buildEffectiveSessionForState(state, nowMs).snapshot
    }

    internal fun acceptedRemoteTrackerIdsForCurrentSession(): Set<String> {
        return buildCurrentSessionSnapshot().plan.acceptedRemoteTrackerIds
    }

    private fun buildEffectiveSessionForState(
        state: TrackerMapUiState,
        nowMs: Long = System.currentTimeMillis(),
    ): TrackerMapEffectiveSession {
        val rt = runtime ?: return MapRenderMath.project(
            TrackerMapEffectiveSessionInput(
                state = state,
                plan = rtFallbackPlan(),
                trailPointLimit = TrackerMapViewModel.TRAIL_POINT_LIMIT,
                visibleTrackerIds = null,
                nowMs = nowMs,
            )
        )
        val groupSelection = rt.resolveGroupModeSelection(state)
        val plan = rt.projectSession(
            state = state,
            groupSelection = groupSelection,
            visibleRosterTrackerIds = rt.visibleMapRosterTrackerIds(),
        )
        val trackers = rt.catalog().trackers
        rt.trailEngine.publishRuntimeHead(rt.recording(), plan)
        val snapshots = rt.dependencies.historyRepository.snapshots.value
        val visibleIds = visibleTrackerIdsForSessionPlan(state, plan)
        val unpublished = MapTrailEngine.unpublishedOverlaysByTracker(
            ingestor = rt.dependencies.historyTrunkIngestor,
            trackerIds = MapTrailEngine.historyTrackerIdsForRender(state, plan, visibleIds),
            trackers = trackers,
        )
        val previous = rt.trailEngine.trail.value
        val trails = MapTrailEngine.trailsFromSnapshots(
            state = state.copy(runtime = rt.recording()),
            plan = plan,
            snapshots = snapshots,
            trackers = trackers,
            trailPointLimit = TrackerMapViewModel.TRAIL_POINT_LIMIT,
            visibleTrackerIds = visibleIds,
            unpublishedOverlaysByTracker = unpublished,
            previousSingleTrail = previous.singleTrail,
            previousMultiTrails = previous.tracksByTrackerId,
            previousRemoteLastPoints = previous.remoteLastPoints,
        )
        return MapRenderMath.project(
            TrackerMapEffectiveSessionInput(
                state = state.copy(runtime = rt.recording()),
                plan = plan,
                trailPointLimit = TrackerMapViewModel.TRAIL_POINT_LIMIT,
                visibleTrackerIds = visibleIds,
                nowMs = nowMs,
                singleTrail = trails.trail,
                allQueueTrailsByTracker = trails.allQueueTrailsByTracker,
                remoteLastPoints = previous.remoteLastPoints,
            )
        )
    }

    internal fun reprojectTrailsFromRepository(reason: String) {
        val rt = runtime ?: return
        if (!rt.sessionEngine.isMapReady || !rt.sessionEngine.isMapSurfaceVisible) return
        if (rt.dependencies.historyRepository.snapshots.value.isEmpty()) return
        rt.ports.viewModelScope.launch {
            rt.withTrailCommit {
                val snapshots = rt.dependencies.historyRepository.snapshots.value
                if (snapshots.isEmpty()) return@withTrailCommit
                GeoVaultCaptureLog.d(
                    TrackerMapViewModel.TAG,
                    "map_update vm_trail_reproject reason=$reason snapshot_keys=${snapshots.size}",
                )
                rt.stateHub.uiStateMutable.update { latest ->
                    rt.trailEngine.applyHistoryTrailsToState(latest, rt.projectSession(latest))
                }
            }
        }
    }

    internal fun buildMapRenderState(): com.geovault.common.maps.render.MapRenderState {
        val snapshot = buildCurrentSessionSnapshot()
        return buildMapRenderState(snapshot)
    }

    private fun buildMapRenderState(
        snapshot: TrackerMapSessionSnapshot
    ): com.geovault.common.maps.render.MapRenderState {
        val rt = runtime ?: return MapRenderMath.buildRenderState(
            session = snapshot,
            cosmetics = TrackerMapRenderCosmetics(
                trackerColorById = emptyMap(),
                trackerDisplayNameById = emptyMap(),
                selectedMapTrackerId = null,
                trackerRenderOrder = emptyList(),
                defaultIconColorHex = GeoVaultColorTokens.Hex.Blue400,
            ),
            accuracy = TrackerMapAccuracyRenderModel(
                fallbackAccuracyByTrackerId = emptyMap(),
                allowAccuracyFallbackByTrackerId = emptySet(),
            ),
        )
        val s = snapshot.uiState
        val renderAllQueueTrailsByTracker = snapshot.renderTrailsByTracker
        val trackerColors = rt.catalog().trackers.associate { it.id to (it.color ?: "") }
        val trackerDisplayNames = rt.catalog().trackers.associate { it.id to it.name }
        val trackerRenderOrder = rt.catalog().trackers.map { it.id }
        val effectiveDisplayedId = rt.displayedTrackerId(s)
        val fallbackAccuracyByTrackerId = buildFallbackAccuracyByTrackerId(s, snapshot.plan)
        val visibleTrackerIds = resolveVisibleAccuracyTrackerIds(
            effectiveDisplayedId = effectiveDisplayedId,
            allQueueTrailsByTracker = renderAllQueueTrailsByTracker,
            remoteLastPoints = snapshot.acceptedRemoteLastPoints,
        )
        val allowAccuracyFallbackByTrackerId = MapRenderMath.resolveAllowedFallbackTrackerIds(
            mode = s.mode,
            selectedTrackerId = rt.catalogSelectedTrackerId(),
            displayedTrackerId = effectiveDisplayedId,
            visibleTrackerIds = visibleTrackerIds,
        )
        return MapRenderMath.buildRenderState(
            session = snapshot,
            cosmetics = TrackerMapRenderCosmetics(
                trackerColorById = trackerColors,
                trackerDisplayNameById = trackerDisplayNames,
                selectedMapTrackerId = MapSessionEngine.resolveRenderSelectedMapTrackerId(
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

    internal fun trailBoundsOrNull(): LatLngBounds? {
        val snapshot = buildCurrentSessionSnapshot()
        return trailBoundsOrNull(snapshot, System.currentTimeMillis())
    }

    private fun trailBoundsOrNull(
        snapshot: TrackerMapSessionSnapshot,
        nowMs: Long,
    ): LatLngBounds? {
        val rt = runtime ?: return MapRenderMath.trailBounds(snapshot.singleTrail)
        val s = snapshot.uiState
        if (s.mode == TrackerMapDisplayMode.ALL_QUEUE || s.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
            val sessionPlan = snapshot.plan
            val visibleTrackerIds = visibleTrackerIdsForSessionPlan(s, sessionPlan).orEmpty()
            val groupBoundsInput = TrackerMapGroupBoundsInput(
                visibleTrackerIds = visibleTrackerIds,
                liveActiveFitEnabled = s.liveActiveFitEnabled,
                fitOnlyActiveTrackers = rt.dependencies.trackerSettingsRepository.getSettings().groupModeFitOnlyActiveTrackers,
                trailsByTracker = snapshot.renderTrailsByTracker,
                remoteLastPoints = snapshot.acceptedRemoteLastPoints,
                acceptedRemoteTrackerIds = sessionPlan.acceptedRemoteTrackerIds,
                trackers = rt.catalog().trackers,
                nowMs = nowMs,
                runtime = snapshot.runtime,
            )
            return when (val resolution = TrackerMapGroupBoundsResolver.resolveOrHold(groupBoundsInput)) {
                is TrackerMapGroupBoundsResolution.Bounds -> resolution.bounds
                TrackerMapGroupBoundsResolution.Hold -> null
                TrackerMapGroupBoundsResolution.NoBounds ->
                    MapRenderMath.trailBounds(snapshot.singleTrail)
                        ?: singlePointBoundsFromRuntime(rt.recording())
            }
        }
        val sessionPlan = snapshot.plan
        return MapRenderMath.trailBounds(snapshot.singleTrail)
            ?: singlePointBoundsFromRuntime(rt.recording(), sessionPlan)
    }

    private fun singlePointBoundsFromRuntime(
        runtimeSnapshot: TrackingRuntimeSnapshot,
        sessionPlan: TrackerMapStreamingPlan? = null,
    ): LatLngBounds? {
        if (sessionPlan != null &&
            sessionPlan.mode == TrackerMapDisplayMode.SINGLE_SESSION &&
            sessionPlan.selectedTrackerId.isNotEmpty() &&
            sessionPlan.displayedTrackerId != sessionPlan.selectedTrackerId
        ) {
            return null
        }
        val lat = runtimeSnapshot.lastTrackedLatitude ?: return null
        val lon = runtimeSnapshot.lastTrackedLongitude ?: return null
        return LatLngBounds.from(lat, lon, lat, lon)
    }

    private fun buildFallbackAccuracyByTrackerId(
        state: TrackerMapUiState,
        sessionPlan: TrackerMapStreamingPlan,
    ): Map<String, Float> {
        val rt = runtime ?: return emptyMap()
        val fallbackByTrackerId = mutableMapOf<String, Float>()
        rt.catalog().trackers.forEach { tracker ->
            val trackerId = tracker.id.trim()
            if (trackerId.isEmpty()) return@forEach
            extractTrackerLatestAccuracyMeters(tracker)?.toFinitePositiveOrNull()?.let { accuracy ->
                fallbackByTrackerId[trackerId] = accuracy
            }
        }
        val selectedTrackerId = rt.catalogSelectedTrackerId().trim()
        rt.recording().lastAccuracyMeters.toFinitePositiveOrNull()?.let { runtimeAccuracy ->
            if (selectedTrackerId.isNotEmpty() && selectedTrackerId in sessionPlan.localOverlayTrackerIds) {
                fallbackByTrackerId[selectedTrackerId] = runtimeAccuracy
            }
        }
        return fallbackByTrackerId
    }

    private fun resolveVisibleAccuracyTrackerIds(
        effectiveDisplayedId: String,
        allQueueTrailsByTracker: Map<String, List<com.geovault.tracker.db.QueuedLocation>>,
        remoteLastPoints: Map<String, com.geovault.tracker.domain.TrackPoint>,
    ): Set<String> {
        return buildSet {
            val displayedId = effectiveDisplayedId.trim()
            if (displayedId.isNotEmpty()) add(displayedId)
            allQueueTrailsByTracker.keys
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach(::add)
            remoteLastPoints.keys
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach(::add)
        }
    }

    private fun visibleTrackerIdsForSessionPlan(
        state: TrackerMapUiState,
        plan: TrackerMapStreamingPlan,
    ): Set<String>? {
        val rt = runtime ?: return null
        return when (state.mode) {
            TrackerMapDisplayMode.GROUP_PLACEHOLDER -> HiddenMapItemsPolicy
                .visibleTrackerIdsForMap(
                    rosterTrackerIds = plan.groupTrackerIds,
                    mapVisibility = rt.catalog().mapVisibility,
                    trackers = rt.catalog().trackers,
                )
            TrackerMapDisplayMode.ALL_QUEUE -> plan.visibleRosterTrackerIds
            TrackerMapDisplayMode.SINGLE_SESSION -> null
        }
    }

    private fun Float?.toFinitePositiveOrNull(): Float? {
        return this?.takeIf { it.isFinite() && it > 0f }
    }

    private fun extractTrackerLatestAccuracyMeters(tracker: Tracker): Float? {
        val accuracyRaw = tracker.point_params?.lastOrNull()?.get("acc") ?: return null
        if (!accuracyRaw.isJsonPrimitive) return null
        val primitive = accuracyRaw.asJsonPrimitive
        return when {
            primitive.isNumber -> primitive.asFloat
            primitive.isString -> primitive.asString.toFloatOrNull()
            else -> null
        }?.takeIf { it.isFinite() }
    }

    private fun rtFallbackPlan(): TrackerMapStreamingPlan {
        return TrackerMapStreamingPlan(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            selectedTrackerId = "",
            displayedTrackerId = "",
            displayedTrackerName = "",
            resolvedGroupId = "",
            groupTrackerIds = emptySet(),
            visibleRosterTrackerIds = emptySet(),
            locallyRecordedTrackerIds = emptySet(),
            remoteSubscriptionIds = emptySet(),
            acceptedRemoteTrackerIds = emptySet(),
            localOverlayTrackerIds = emptySet(),
            trailReloadPlan = com.geovault.tracker.presentation.TrackerMapTrailReloadPlan(
                source = com.geovault.tracker.presentation.TrackerMapTrailSource.SINGLE_QUEUE,
                singleTrackerId = "",
                activeTrackerId = "",
            ),
        )
    }
}

internal object MapRenderMath {
    const val MAX_TRACK_JUMP_METERS: Float = 5f * 1609.344f
    const val MAX_TRACK_TIME_GAP_MS: Long = 5L * 60L * 1_000L
    const val MAX_TRACK_TIME_GAP_WHILE_RECORDING_MS: Long = 15L * 60L * 1_000L
    private const val LIVE_DRAW_TRAIL_POINT_LIMIT: Int = 4000
    private const val TAG = "MapRenderEngine"
    private val accuracyCircleResolver = com.geovault.tracker.presentation.TrackerAccuracyCircleResolver()

    fun buildSession(input: TrackerMapSessionBuildInput): TrackerMapSessionSnapshot {
        val state = input.state
        val plan = input.plan
        val acceptedRemoteLastPoints = input.remoteLastPoints.filterKeys {
            it in plan.acceptedRemoteTrackerIds || it in plan.locallyRecordedTrackerIds
        }
        val normalizedTrails = input.localRuntimeOverlayTrails.mapKeys { it.key.trim() }
            .filterKeys { it.isNotEmpty() }
        val candidateKeys = normalizedTrails.keys + acceptedRemoteLastPoints.keys
        val rosterFilteredKeys = input.visibleTrackerIds?.let { filter ->
            candidateKeys.filter { it in filter }.toSet()
        } ?: candidateKeys
        val tracks = rosterFilteredKeys.associateWith { trackerId ->
            val renderTrail = normalizedTrails[trackerId].orEmpty()
            TrackerTrackModel(
                trackerId = trackerId,
                renderTrail = renderTrail,
                remoteHead = acceptedRemoteLastPoints[trackerId],
            )
        }
        return TrackerMapSessionSnapshot(
            uiState = state,
            plan = plan,
            runtime = state.runtime,
            singleTrail = input.singleTrail,
            tracks = tracks,
            acceptedRemoteLastPoints = acceptedRemoteLastPoints,
        )
    }

    fun buildRenderState(
        session: TrackerMapSessionSnapshot,
        cosmetics: TrackerMapRenderCosmetics,
        accuracy: TrackerMapAccuracyRenderModel = TrackerMapAccuracyRenderModel(),
    ): com.geovault.common.maps.render.MapRenderState {
        return buildRenderState(
            mode = session.mode,
            trail = session.singleTrail,
            runtime = session.runtime,
            remoteLastPoints = session.acceptedRemoteLastPoints,
            acceptedRemoteTrackerIds = session.plan.acceptedRemoteTrackerIds,
            allQueueTrailsByTracker = session.renderTrailsByTracker,
            trackerColorById = cosmetics.trackerColorById,
            trackerDisplayNameById = cosmetics.trackerDisplayNameById,
            displayedTrackerId = session.plan.displayedTrackerId,
            selectedMapTrackerId = cosmetics.selectedMapTrackerId,
            trackerRenderOrder = cosmetics.trackerRenderOrder,
            fallbackAccuracyMeters = accuracy.fallbackAccuracyMeters,
            allowAccuracyFallback = accuracy.allowAccuracyFallback,
            fallbackAccuracyByTrackerId = accuracy.fallbackAccuracyByTrackerId,
            allowAccuracyFallbackByTrackerId = accuracy.allowAccuracyFallbackByTrackerId,
            defaultIconColorHex = cosmetics.defaultIconColorHex,
            displayedTrackerName = session.plan.displayedTrackerName,
        )
    }

    fun buildRenderState(
        mode: TrackerMapDisplayMode,
        trail: List<com.geovault.tracker.db.QueuedLocation>,
        runtime: TrackingRuntimeSnapshot,
        remoteLastPoints: Map<String, com.geovault.tracker.domain.TrackPoint> = emptyMap(),
        acceptedRemoteTrackerIds: Set<String> = emptySet(),
        allQueueTrailsByTracker: Map<String, List<com.geovault.tracker.db.QueuedLocation>> = emptyMap(),
        trackerColorById: Map<String, String> = emptyMap(),
        trackerDisplayNameById: Map<String, String> = emptyMap(),
        displayedTrackerId: String = "",
        selectedMapTrackerId: String? = null,
        trackerRenderOrder: List<String> = emptyList(),
        fallbackAccuracyMeters: Float? = null,
        allowAccuracyFallback: Boolean = false,
        fallbackAccuracyByTrackerId: Map<String, Float> = emptyMap(),
        allowAccuracyFallbackByTrackerId: Set<String> = emptySet(),
        defaultIconColorHex: String = com.geovault.tracker.presentation.TrackerMapIconIds.DEFAULT_COLOR_HEX,
        displayedTrackerName: String = "",
    ): com.geovault.common.maps.render.MapRenderState {
        val liveHeads = liveHeadsIncludingRecording(remoteLastPoints, runtime)
        val singleIconId = singleTrackerIconId(
            trackerColorById = trackerColorById,
            displayedTrackerId = displayedTrackerId,
            selectedTrackerId = "",
            fallbackColorHex = defaultIconColorHex,
        )
        val singleLineColorHex = com.geovault.tracker.presentation.TrackerMapIconIds.parseSpec(singleIconId)?.colorHex
            ?: com.geovault.tracker.presentation.TrackerMapIconIds.DEFAULT_COLOR_HEX
        val effectiveDisplayedTrackerId = displayedTrackerId.trim()
        val renderTrail = if (mode == TrackerMapDisplayMode.SINGLE_SESSION) {
            MapTrailEngine.mergeLiveDrawSingle(
                mappedTrail = trail,
                unpublishedOverlay = emptyList(),
                remoteLastPoint = liveHeads[effectiveDisplayedTrackerId],
                runtime = runtime,
                displayedTrackerId = displayedTrackerId,
                trailPointLimit = LIVE_DRAW_TRAIL_POINT_LIMIT,
            )
        } else {
            trail
        }
        val renderMultiTrails = if (
            mode == TrackerMapDisplayMode.ALL_QUEUE || mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER
        ) {
            MapTrailEngine.mergeLiveDrawMulti(
                mappedTrails = allQueueTrailsByTracker,
                unpublishedOverlaysByTracker = emptyMap(),
                remoteLastPoints = liveHeads.filterKeys { it in acceptedRemoteTrackerIds },
                runtime = runtime,
                mode = mode,
                groupTrackerIds = allQueueTrailsByTracker.keys + acceptedRemoteTrackerIds,
                trailPointLimit = LIVE_DRAW_TRAIL_POINT_LIMIT,
            )
        } else {
            allQueueTrailsByTracker
        }
        val resolveState = TrackerMapUiState(
            mode = mode,
            displayedTrackerId = displayedTrackerId,
            runtime = runtime,
        )
        val lines = buildTrailLines(
            mode = mode,
            effectiveTrail = renderTrail,
            allQueueTrailsByTracker = renderMultiTrails,
            trackerColorById = trackerColorById,
            singleTrackerLineColorHex = singleLineColorHex,
            runtime = runtime,
        )
        val markerFeatures = mutableListOf<TrackerMarkerRenderFeature>()
        if (mode == TrackerMapDisplayMode.SINGLE_SESSION) {
            val resolved = resolveLastPoint(
                state = resolveState,
                trackerId = effectiveDisplayedTrackerId,
                tracker = null,
                acceptedRemoteTrackerIds = acceptedRemoteTrackerIds,
                singleTrail = renderTrail,
                allQueueTrailsByTracker = renderMultiTrails,
                remoteLastPoints = liveHeads,
            )
            val lastLat = resolved?.latitude
            val lastLon = resolved?.longitude
            val lastAccuracy = resolved?.accuracyMeters
            val lastRotation = if (lastLat != null && lastLon != null) {
                markerDirectionDegrees(renderTrail, lastLat, lastLon)
            } else {
                0f
            }
            if (lastLat != null && lastLon != null &&
                com.geovault.common.maps.core.isValidMapLibreGeographicLatLng(lastLat, lastLon)
            ) {
                markerFeatures.add(
                    TrackerMarkerRenderFeature(
                        marker = com.geovault.common.maps.render.MapRenderPoint(
                            id = "last-fix",
                            latitude = lastLat,
                            longitude = lastLon,
                            title = singleMarkerTitle(displayedTrackerId, displayedTrackerName, runtime, trackerDisplayNameById),
                            iconImageId = singleIconId,
                            iconRotationDegrees = lastRotation,
                        ),
                        sourceAccuracyMeters = lastAccuracy,
                    )
                )
            }
        } else {
            val selectedMarkerTrackerId = selectedMapTrackerId?.trim().orEmpty()
            val orderedTrackerIds = buildList {
                trackerRenderOrder.map { it.trim() }.filter { it.isNotEmpty() }.forEach { id ->
                    if (id in renderMultiTrails || id in acceptedRemoteTrackerIds) add(id)
                }
                renderMultiTrails.keys.sorted().forEach { id ->
                    if (id !in this) add(id)
                }
                acceptedRemoteTrackerIds.sorted().forEach { id ->
                    if (id !in this) add(id)
                }
            }
            orderedTrackerIds.forEach { trackerId ->
                val trackerTrail = renderMultiTrails[trackerId].orEmpty()
                val resolved = resolveLastPoint(
                    state = resolveState,
                    trackerId = trackerId,
                    tracker = null,
                    acceptedRemoteTrackerIds = acceptedRemoteTrackerIds,
                    singleTrail = renderTrail,
                    allQueueTrailsByTracker = renderMultiTrails,
                    remoteLastPoints = liveHeads,
                ) ?: return@forEach
                if (!com.geovault.common.maps.core.isValidMapLibreGeographicLatLng(resolved.latitude, resolved.longitude)) {
                    return@forEach
                }
                val rotation = markerDirectionDegrees(trackerTrail, resolved.latitude, resolved.longitude)
                val iconId = multiTrackerIconId(
                    trackerId = trackerId,
                    trackerColorById = trackerColorById,
                    selectedMapTrackerId = selectedMarkerTrackerId,
                    fallbackColorHex = defaultIconColorHex,
                )
                markerFeatures.add(
                    TrackerMarkerRenderFeature(
                        marker = com.geovault.common.maps.render.MapRenderPoint(
                            id = "remote-$trackerId",
                            latitude = resolved.latitude,
                            longitude = resolved.longitude,
                            title = trackerDisplayNameById[trackerId]?.trim()?.takeIf { it.isNotEmpty() } ?: trackerId,
                            iconImageId = iconId,
                            iconRotationDegrees = rotation,
                        ),
                        sourceAccuracyMeters = resolved.accuracyMeters,
                    )
                )
            }
        }

        val markers = markerFeatures.map { it.marker }
        val sourceAccuracyByMarkerId = markerFeatures
            .mapNotNull { feature ->
                feature.sourceAccuracyMeters
                    ?.takeIf { it.isFinite() && it > 0f }
                    ?.let { feature.marker.id to it }
            }
            .toMap()
        val normalizedFallbackAccuracyByTracker = fallbackAccuracyByTrackerId.mapKeys { it.key.trim() }
        val normalizedAllowFallbackTrackerIds = allowAccuracyFallbackByTrackerId
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val defaultSingleTrackerId = displayedTrackerId.trim()
        val polygons = buildAccuracyPolygons(
            markers = markers,
            markerColorById = buildMarkerColorById(
                mode = mode,
                markers = markers,
                trackerColorById = trackerColorById,
                singleLineColorHex = singleLineColorHex,
            ),
            fallbackAccuracyMeters = fallbackAccuracyMeters,
            allowAccuracyFallback = allowAccuracyFallback,
            sourceAccuracyByMarkerId = sourceAccuracyByMarkerId,
            fallbackAccuracyByTrackerId = normalizedFallbackAccuracyByTracker,
            allowAccuracyFallbackByTrackerId = normalizedAllowFallbackTrackerIds,
            defaultSingleTrackerId = defaultSingleTrackerId,
        )

        return com.geovault.common.maps.render.MapRenderState(
            points = markers,
            lines = lines,
            polygons = polygons,
        )
    }

    fun trailBounds(trail: List<com.geovault.tracker.db.QueuedLocation>): org.maplibre.android.geometry.LatLngBounds? {
        val valid = trail.filter {
            com.geovault.common.maps.core.isValidMapLibreGeographicLatLng(it.latitude, it.longitude)
        }
        if (valid.isEmpty()) return null
        val latLngs = valid.map { org.maplibre.android.geometry.LatLng(it.latitude, it.longitude) }
        if (latLngs.size == 1) {
            val p = latLngs.first()
            return org.maplibre.android.geometry.LatLngBounds.from(p.latitude, p.longitude, p.latitude, p.longitude)
        }
        val b = org.maplibre.android.geometry.LatLngBounds.Builder()
        latLngs.forEach { b.include(it) }
        return b.build()
    }

    fun multiTrailBounds(
        trailsByTracker: Map<String, List<com.geovault.tracker.db.QueuedLocation>>,
    ): org.maplibre.android.geometry.LatLngBounds? {
        if (trailsByTracker.isEmpty()) return null
        return trailBounds(trailsByTracker.values.flatten())
    }

    fun remoteLastPointBounds(
        remoteLastPoints: Map<String, com.geovault.tracker.domain.TrackPoint>,
    ): org.maplibre.android.geometry.LatLngBounds? {
        val valid = remoteLastPoints.values
            .filter { com.geovault.common.maps.core.isValidMapLibreGeographicLatLng(it.latitude, it.longitude) }
            .map { org.maplibre.android.geometry.LatLng(it.latitude, it.longitude) }
        if (valid.isEmpty()) return null
        if (valid.size == 1) {
            val point = valid.first()
            return org.maplibre.android.geometry.LatLngBounds.from(point.latitude, point.longitude, point.latitude, point.longitude)
        }
        val bounds = org.maplibre.android.geometry.LatLngBounds.Builder()
        valid.forEach { bounds.include(it) }
        return bounds.build()
    }

    fun mergeBounds(
        first: org.maplibre.android.geometry.LatLngBounds?,
        second: org.maplibre.android.geometry.LatLngBounds?,
    ): org.maplibre.android.geometry.LatLngBounds? {
        if (first == null) return second
        if (second == null) return first
        val bounds = org.maplibre.android.geometry.LatLngBounds.Builder()
        bounds.include(first.northEast)
        bounds.include(first.southWest)
        bounds.include(second.northEast)
        bounds.include(second.southWest)
        return bounds.build()
    }

    fun resolveLastPoint(
        snapshot: TrackerMapSessionSnapshot,
        trackerId: String,
        tracker: Tracker? = null,
    ): com.geovault.tracker.presentation.TrackerMapResolvedPoint? {
        return resolveLastPoint(trackerId, snapshot.acceptedRemoteLastPoints)
    }

    fun resolveLastPoint(
        state: TrackerMapUiState,
        trackerId: String,
        tracker: Tracker?,
        acceptedRemoteTrackerIds: Set<String>,
        singleTrail: List<com.geovault.tracker.db.QueuedLocation> = emptyList(),
        allQueueTrailsByTracker: Map<String, List<com.geovault.tracker.db.QueuedLocation>> = emptyMap(),
        remoteLastPoints: Map<String, com.geovault.tracker.domain.TrackPoint> = emptyMap(),
    ): com.geovault.tracker.presentation.TrackerMapResolvedPoint? {
        return resolveLastPoint(trackerId, remoteLastPoints)
    }

    fun resolveLastPoint(
        trackerId: String,
        liveHeads: Map<String, com.geovault.tracker.domain.TrackPoint>,
    ): com.geovault.tracker.presentation.TrackerMapResolvedPoint? {
        val normalizedId = trackerId.trim()
        if (normalizedId.isEmpty()) return null
        val head = liveHeads[normalizedId] ?: return null
        return com.geovault.tracker.presentation.TrackerMapResolvedPoint(
            latitude = head.latitude,
            longitude = head.longitude,
            lastUpdatedMs = head.timeMs.takeIf { it > 0L },
            accuracyMeters = head.accuracyMeters,
        )
    }

    fun resolveLastReportedAtMs(
        trackerId: String,
        locallyRecordedTrackerId: String,
        lastPointSentAtMs: Long,
        resolverLastUpdatedMs: Long?,
    ): Long? {
        val normalized = trackerId.trim()
        if (normalized.isEmpty()) return resolverLastUpdatedMs
        if (normalized == locallyRecordedTrackerId.trim()) {
            return lastPointSentAtMs.takeIf { it > 0L }
        }
        return resolverLastUpdatedMs
    }

    fun resolveLastReportedAtMs(
        trackerId: String,
        recording: TrackingRuntimeSnapshot,
        resolverLastUpdatedMs: Long?,
    ): Long? {
        return resolveLastReportedAtMs(
            trackerId = trackerId,
            locallyRecordedTrackerId = recording.locallyRecordedTrackerId,
            lastPointSentAtMs = recording.lastPointSentAtMs,
            resolverLastUpdatedMs = resolverLastUpdatedMs,
        )
    }

    fun resolveAllowedFallbackTrackerIds(
        mode: TrackerMapDisplayMode,
        selectedTrackerId: String,
        displayedTrackerId: String,
        visibleTrackerIds: Set<String>,
    ): Set<String> {
        val visibleIds = visibleTrackerIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (visibleIds.isEmpty()) return emptySet()
        val selectedId = selectedTrackerId.trim()
        val displayedId = displayedTrackerId.trim()
        return when (mode) {
            TrackerMapDisplayMode.SINGLE_SESSION -> {
                when {
                    displayedId.isNotEmpty() && displayedId in visibleIds -> setOf(displayedId)
                    selectedId.isNotEmpty() && selectedId in visibleIds -> setOf(selectedId)
                    else -> visibleIds
                }
            }
            TrackerMapDisplayMode.ALL_QUEUE,
            TrackerMapDisplayMode.GROUP_PLACEHOLDER -> visibleIds
        }
    }

    fun liveHeadsIncludingRecording(
        liveHeads: Map<String, com.geovault.tracker.domain.TrackPoint>,
        recording: TrackingRuntimeSnapshot,
    ): Map<String, com.geovault.tracker.domain.TrackPoint> {
        val trackerId = recording.locallyRecordedTrackerId.trim()
        val head = MapTrailEngine.runtimeHeadTrackPoint(recording, trackerId) ?: return liveHeads
        val existing = liveHeads[trackerId]
        if (existing != null && existing.timeMs > head.timeMs) return liveHeads
        return liveHeads + (trackerId to head)
    }

    fun project(input: TrackerMapEffectiveSessionInput): TrackerMapEffectiveSession {
        val state = input.state
        val plan = input.plan
        val sourceSingle = input.singleTrail
        val sourceMulti = input.allQueueTrailsByTracker
        val overlaidSingle = singleTrailWithLocalRuntimeOverlay(
            mode = state.mode,
            runtime = state.runtime,
            displayedTrackerId = plan.displayedTrackerId,
            trail = sourceSingle,
            trailPointLimit = input.trailPointLimit,
        )
        val overlaidMulti = allQueueTrailsWithLocalRuntimeOverlay(
            mode = state.mode,
            runtime = state.runtime,
            groupTrackerIds = plan.groupTrackerIds,
            allQueueTrailsByTracker = sourceMulti,
            trailPointLimit = input.trailPointLimit,
        )
        val snapshot = buildSession(
            TrackerMapSessionBuildInput(
                state = state,
                plan = plan,
                singleTrail = overlaidSingle,
                localRuntimeOverlayTrails = overlaidMulti,
                remoteLastPoints = liveHeadsIncludingRecording(input.remoteLastPoints, state.runtime),
                visibleTrackerIds = input.visibleTrackerIds,
                nowMs = input.nowMs,
            )
        )
        val liveHead = resolveLiveHead(snapshot)
        val projectSignature =
            "mode=${state.mode}|displayed=${plan.displayedTrackerId}|single=${sourceSingle.size}->${snapshot.singleTrail.size}|" +
                "multi=${snapshot.renderTrailsByTracker.mapValues { it.value.size }}|liveHead=$liveHead"
        if (CaptureLogThrottle.shouldLogOnChange("effective_project", projectSignature)) {
            GeoVaultCaptureLog.d(
                TAG,
                "map_draw_session mode=${state.mode} displayed=${plan.displayedTrackerId} " +
                    "singleRaw=${sourceSingle.size} singleDrawn=${snapshot.singleTrail.size} " +
                    "multiRaw=${sourceMulti.mapValues { it.value.size }} " +
                    "multiDrawn=${snapshot.renderTrailsByTracker.mapValues { it.value.size }} " +
                    "visible=${input.visibleTrackerIds?.sorted()} " +
                    "liveHead=$liveHead",
            )
        }
        return TrackerMapEffectiveSession(
            snapshot = snapshot,
            liveHead = liveHead,
        )
    }

    fun normalizedColorOrDefault(
        rawColor: String?,
        fallbackColorHex: String = TrackerMapIconIds.DEFAULT_COLOR_HEX,
    ): String {
        return GeoVaultColorHex.normalizeHashPrefix(rawColor) ?: fallbackColorHex
    }

    fun singleTrackerIconId(
        trackerColorById: Map<String, String>,
        displayedTrackerId: String,
        selectedTrackerId: String,
        fallbackColorHex: String = TrackerMapIconIds.DEFAULT_COLOR_HEX,
    ): String {
        val activeTrackerId = displayedTrackerId.trim().ifBlank { selectedTrackerId.trim() }
        val color = normalizedColorOrDefault(trackerColorById[activeTrackerId], fallbackColorHex)
        return TrackerMapIconIds.selectedForColor(color)
    }

    fun multiTrackerIconId(
        trackerId: String,
        trackerColorById: Map<String, String>,
        selectedMapTrackerId: String?,
        fallbackColorHex: String = TrackerMapIconIds.DEFAULT_COLOR_HEX,
    ): String {
        val normalizedTrackerId = trackerId.trim()
        val selectedId = selectedMapTrackerId?.trim().orEmpty()
        val color = normalizedColorOrDefault(trackerColorById[normalizedTrackerId], fallbackColorHex)
        return if (selectedId.isNotBlank() && selectedId == normalizedTrackerId) {
            TrackerMapIconIds.selectedForColor(color)
        } else {
            TrackerMapIconIds.simpleForColor(color)
        }
    }

    fun composesWithSelectionLock(mode: TrackerMapDisplayMode): Boolean =
        mode == TrackerMapDisplayMode.SINGLE_SESSION

    fun resolveLiveActiveFitLockArmed(singleTrackerLocked: Boolean): Boolean = singleTrackerLocked

    fun resolveLiveActiveFitVisibility(input: LiveActiveFitInput): LiveActiveFitVisibility {
        val singleTrackerVisible = input.mode == TrackerMapDisplayMode.SINGLE_SESSION &&
            input.hasTrailPoints &&
            input.hasMultipleTrackersOnMap
        if (!singleTrackerVisible || input.isSelectedDefaultTracker) {
            return LiveActiveFitVisibility(showButton = false, buttonEnabled = false)
        }
        val toggleEnabled = input.followLockArmed
        return LiveActiveFitVisibility(
            showButton = toggleEnabled,
            buttonEnabled = toggleEnabled,
        )
    }

    fun resolveGpsAccuracyIndicator(runtime: TrackingRuntimeSnapshot): TrackerMapGpsAccuracyIndicatorUiModel {
        if (!runtime.gpsCollecting) {
            return TrackerMapGpsAccuracyIndicatorUiModel(isVisible = false)
        }
        val noGoodFix = runtime.lastAccuracyMeters == null ||
            runtime.lastAccuracyMeters > runtime.effectiveAccuracyThresholdMeters
        return TrackerMapGpsAccuracyIndicatorUiModel(isVisible = noGoodFix)
    }

    fun evaluateUserLocation(
        input: TrackerMapUserLocationInput,
        commonDecision: GeoVaultMapLocationSessionDecision? = null,
        commonPolicy: GeoVaultMapLocationSessionPolicy = GeoVaultMapLocationSessionPolicy(),
    ): TrackerMapUserLocationDecision {
        val displayedId = input.displayedTrackerId.trim()
        val recordedId = input.locallyRecordedTrackerId.trim()
        val ownRecordedTrackerOnScreen = recordedId.isNotEmpty() && displayedId == recordedId
        val blockers = linkedSetOf<TrackerMapUserLocationBlocker>()
        if (!input.isMapActive) blockers += TrackerMapUserLocationBlocker.MapInactive
        if (!input.hasLocationPermission) blockers += TrackerMapUserLocationBlocker.MissingPermission
        if (!input.isMapReady) blockers += TrackerMapUserLocationBlocker.MapNotReady
        if (!input.userLocationRequestedThisSession) {
            blockers += TrackerMapUserLocationBlocker.LocationNotRequestedThisSession
        }
        if (ownRecordedTrackerOnScreen) {
            blockers += TrackerMapUserLocationBlocker.OwnRecordedTrackerOnScreen
        }
        val resolvedCommon = commonDecision ?: commonPolicy.decide(
            GeoVaultMapLocationSessionInput(
                isActive = input.isMapActive,
                hasLocationPermission = input.hasLocationPermission,
                isMapReady = input.isMapReady,
                userLocationRequested = input.userLocationRequestedThisSession,
                positionFollowDesired = false,
                headingFollowDesired = false,
            ),
        )
        return TrackerMapUserLocationDecision(
            shouldStreamGps = resolvedCommon.shouldStreamGps && !ownRecordedTrackerOnScreen,
            shouldEnablePuck = resolvedCommon.shouldEnablePuck && !ownRecordedTrackerOnScreen,
            blockers = blockers,
        )
    }

    fun resolveLockFab(input: TrackerMapLockFabInput): TrackerMapLockFabBehavior {
        val displayedTrackerId = input.displayedTrackerId.trim()
        return when {
            input.mode == TrackerMapDisplayMode.SINGLE_SESSION && displayedTrackerId.isNotEmpty() ->
                TrackerMapLockFabBehavior.SelectionLock(
                    displayedTrackerId = displayedTrackerId,
                    isLocked = input.selectionLockTrackerId.trim() == displayedTrackerId,
                )
            input.mode == TrackerMapDisplayMode.ALL_QUEUE ||
                input.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER ->
                TrackerMapLockFabBehavior.LiveActiveFit(input.liveActiveFitEnabled)
            else -> TrackerMapLockFabBehavior.FollowLock(input.followLockEnabled)
        }
    }

    fun shouldShowMyLocationFab(
        mode: TrackerMapDisplayMode,
        displayedTrackerId: String,
        selectedTrackerId: String,
    ): Boolean {
        val selected = selectedTrackerId.trim()
        val effective = displayedTrackerId.trim().ifBlank { selected }
        val isSelectedDefaultSingleSession =
            mode == TrackerMapDisplayMode.SINGLE_SESSION &&
                effective.isNotEmpty() &&
                effective == selected
        return !isSelectedDefaultSingleSession
    }

    val includeDefaultFabColumnPadding: Boolean = true
    val FallbackTopLeftChipViewportReserveTopDp = 40.dp
    val FallbackSelectionPanelViewportReserveBottomDp = 120.dp
    private val TopLeftChipViewportReserveLeftDp = 88.dp

    val mapPaddingDp: GeoVaultMapPaddingDp
        get() = buildMapPaddingDp(FallbackTopLeftChipViewportReserveTopDp)

    fun computeBoundsFitPaddingPx(
        density: Density,
        topLeftChipReserveDp: Dp = FallbackTopLeftChipViewportReserveTopDp,
        selectionPanelReserveDp: Dp = 0.dp,
    ): IntArray {
        return GeoVaultMapPaddingPolicy(
            includeDefaultFabColumnPadding = includeDefaultFabColumnPadding,
            mapPaddingDp = buildMapPaddingDp(topLeftChipReserveDp, selectionPanelReserveDp),
        ).computeBoundsFitPaddingPx(density)
    }

    private fun buildMapPaddingDp(
        topReserveDp: Dp,
        bottomReserveDp: Dp = 0.dp,
    ) = GeoVaultMapPaddingDp(
        left = TopLeftChipViewportReserveLeftDp,
        top = topReserveDp,
        bottom = bottomReserveDp,
    )

    fun resolveCameraDirective(input: TrackerMapCameraDirectiveInput): TrackerMapCameraDirectiveResolution {
        val bothLocksEngaged = input.selectionLockEnabled && input.liveActiveFitEnabled
        if (input.selectionLockEnabled && !bothLocksEngaged) {
            return selectionLockCenterOrHold(input)
        }
        if (bothLocksEngaged) {
            liveActiveFitOrHold(input)?.let { return it }
            return selectionLockCenterOrHold(input)
        }
        if (input.followLockEnabled) {
            return if (
                input.gpsCollecting &&
                input.followTargetLat != null &&
                input.followTargetLon != null
            ) {
                TrackerMapCameraDirectiveResolution(
                    reason = TrackerMapCameraDirective.Reason.FollowLock,
                    centerLat = input.followTargetLat,
                    centerLon = input.followTargetLon,
                    bounds = null,
                )
            } else {
                TrackerMapCameraDirectiveResolution.None
            }
        }
        if (input.liveActiveFitEnabled) {
            liveActiveFitOrHold(input)?.let { return it }
        }
        if (input.bounds != null) {
            return TrackerMapCameraDirectiveResolution(
                reason = TrackerMapCameraDirective.Reason.InitialFit,
                centerLat = null,
                centerLon = null,
                bounds = input.bounds,
            )
        }
        return TrackerMapCameraDirectiveResolution.None
    }

    private fun liveActiveFitOrHold(input: TrackerMapCameraDirectiveInput): TrackerMapCameraDirectiveResolution? {
        if (input.userOwnsZoom) {
            val lat = input.followTargetLat
                ?: input.selectionLockLat
                ?: input.bounds?.let { (it.latitudeNorth + it.latitudeSouth) / 2.0 }
            val lon = input.followTargetLon
                ?: input.selectionLockLon
                ?: input.bounds?.let { (it.longitudeEast + it.longitudeWest) / 2.0 }
            if (lat != null && lon != null) {
                return TrackerMapCameraDirectiveResolution(
                    reason = TrackerMapCameraDirective.Reason.LiveActiveFit,
                    centerLat = lat,
                    centerLon = lon,
                    bounds = null,
                )
            }
            return null
        }
        val bounds = input.bounds ?: return null
        return TrackerMapCameraDirectiveResolution(
            reason = TrackerMapCameraDirective.Reason.LiveActiveFit,
            centerLat = null,
            centerLon = null,
            bounds = bounds,
        )
    }

    private fun selectionLockCenterOrHold(input: TrackerMapCameraDirectiveInput): TrackerMapCameraDirectiveResolution {
        val lat = input.selectionLockLat
        val lon = input.selectionLockLon
        return if (lat != null && lon != null) {
            TrackerMapCameraDirectiveResolution(
                reason = TrackerMapCameraDirective.Reason.SelectionLock,
                centerLat = lat,
                centerLon = lon,
                bounds = null,
            )
        } else {
            TrackerMapCameraDirectiveResolution.None
        }
    }

    fun mintCameraDirective(
        resolution: TrackerMapCameraDirectiveResolution,
        id: Long,
        generation: Long,
    ): TrackerMapCameraDirective {
        return when (resolution.reason) {
            TrackerMapCameraDirective.Reason.SelectionLock,
            TrackerMapCameraDirective.Reason.FollowLock -> {
                val lat = resolution.centerLat
                val lon = resolution.centerLon
                if (lat != null && lon != null) {
                    TrackerMapCameraDirective.CenterOnPoint(
                        latitude = lat,
                        longitude = lon,
                        reason = resolution.reason,
                        id = id,
                        generation = generation,
                    )
                } else {
                    TrackerMapCameraDirective.None(id = id, generation = generation)
                }
            }
            TrackerMapCameraDirective.Reason.LiveActiveFit -> {
                val lat = resolution.centerLat
                val lon = resolution.centerLon
                if (lat != null && lon != null && resolution.bounds == null) {
                    TrackerMapCameraDirective.CenterOnPoint(
                        latitude = lat,
                        longitude = lon,
                        reason = resolution.reason,
                        id = id,
                        generation = generation,
                    )
                } else {
                    val bounds = resolution.bounds
                    if (bounds != null) {
                        TrackerMapCameraDirective.FitBounds(
                            bounds = bounds,
                            mode = TrackerMapFitTrailMode.Instant,
                            reason = resolution.reason,
                            id = id,
                            generation = generation,
                        )
                    } else {
                        TrackerMapCameraDirective.None(id = id, generation = generation)
                    }
                }
            }
            TrackerMapCameraDirective.Reason.InitialFit -> {
                val bounds = resolution.bounds
                if (bounds != null) {
                    TrackerMapCameraDirective.FitBounds(
                        bounds = bounds,
                        mode = TrackerMapFitTrailMode.Instant,
                        reason = resolution.reason,
                        id = id,
                        generation = generation,
                    )
                } else {
                    TrackerMapCameraDirective.None(id = id, generation = generation)
                }
            }
            TrackerMapCameraDirective.Reason.ExplicitFit,
            TrackerMapCameraDirective.Reason.NoOp ->
                TrackerMapCameraDirective.None(id = id, generation = generation)
        }
    }

    fun resolveLiveHead(snapshot: TrackerMapSessionSnapshot): Pair<Double, Double>? {
        val trackerId = liveHeadTrackerId(snapshot)
        if (trackerId.isEmpty()) return null
        val resolved = resolveLastPoint(
            snapshot = snapshot,
            trackerId = trackerId,
            tracker = null,
        ) ?: return null
        return resolved.latitude to resolved.longitude
    }

    fun allQueueTrailsWithLocalRuntimeOverlay(
        mode: TrackerMapDisplayMode,
        runtime: TrackingRuntimeSnapshot,
        groupTrackerIds: Set<String>,
        allQueueTrailsByTracker: Map<String, List<com.geovault.tracker.db.QueuedLocation>>,
        trailPointLimit: Int,
    ): Map<String, List<com.geovault.tracker.db.QueuedLocation>> {
        if (mode != TrackerMapDisplayMode.ALL_QUEUE && mode != TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
            return allQueueTrailsByTracker
        }
        if (!runtime.localRecordingActive) return allQueueTrailsByTracker
        val trackerId = runtime.locallyRecordedTrackerId
        if (trackerId.isEmpty()) return allQueueTrailsByTracker
        if (mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER && trackerId !in groupTrackerIds) {
            return allQueueTrailsByTracker
        }
        val currentTrail = allQueueTrailsByTracker[trackerId].orEmpty()
        val nextTrail = trailWithLocalRuntimeOverlay(
            runtime = runtime,
            trackerId = trackerId,
            currentTrail = currentTrail,
            trailPointLimit = trailPointLimit,
        )
        if (nextTrail === currentTrail) {
            GeoVaultCaptureLog.v(
                TAG,
                "map_update effective_overlay_multi_skipped mode=$mode tracker=$trackerId current=${currentTrail.size}"
            )
            return allQueueTrailsByTracker
        }
        GeoVaultCaptureLog.d(
            TAG,
            "map_update effective_overlay_multi_added mode=$mode tracker=$trackerId " +
                "from=${currentTrail.size} to=${nextTrail.size} runtimeTs=${runtime.lastTrackedTimestampMs}"
        )
        return allQueueTrailsByTracker.toMutableMap().apply {
            this[trackerId] = nextTrail
        }
    }

    fun singleTrailWithLocalRuntimeOverlay(
        mode: TrackerMapDisplayMode,
        runtime: TrackingRuntimeSnapshot,
        displayedTrackerId: String,
        trail: List<com.geovault.tracker.db.QueuedLocation>,
        trailPointLimit: Int,
    ): List<com.geovault.tracker.db.QueuedLocation> {
        if (mode != TrackerMapDisplayMode.SINGLE_SESSION) return trail
        if (!runtime.localRecordingActive) return trail
        val trackerId = runtime.locallyRecordedTrackerId
        if (trackerId.isEmpty()) return trail
        val effectiveDisplayedId = displayedTrackerId.trim().ifBlank { trackerId }
        if (effectiveDisplayedId != trackerId) return trail
        val nextTrail = trailWithLocalRuntimeOverlay(
            runtime = runtime,
            trackerId = trackerId,
            currentTrail = trail,
            trailPointLimit = trailPointLimit,
        )
        if (nextTrail !== trail) {
            GeoVaultCaptureLog.d(
                TAG,
                "map_update effective_overlay_single_added tracker=$trackerId displayed=$effectiveDisplayedId " +
                    "from=${trail.size} to=${nextTrail.size} runtimeTs=${runtime.lastTrackedTimestampMs}"
            )
        } else {
            GeoVaultCaptureLog.v(
                TAG,
                "map_update effective_overlay_single_skipped tracker=$trackerId displayed=$effectiveDisplayedId current=${trail.size}"
            )
        }
        return nextTrail
    }

    internal fun maxTimeGapMsForRuntime(runtime: TrackingRuntimeSnapshot): Long {
        return if (runtime.localRecordingActive) {
            MAX_TRACK_TIME_GAP_WHILE_RECORDING_MS
        } else {
            MAX_TRACK_TIME_GAP_MS
        }
    }

    private fun liveHeadTrackerId(snapshot: TrackerMapSessionSnapshot): String {
        return when (snapshot.mode) {
            TrackerMapDisplayMode.SINGLE_SESSION -> {
                snapshot.plan.displayedTrackerId.trim().ifBlank {
                    snapshot.plan.selectedTrackerId.trim()
                }
            }
            TrackerMapDisplayMode.ALL_QUEUE,
            TrackerMapDisplayMode.GROUP_PLACEHOLDER -> {
                snapshot.runtime.locallyRecordedTrackerId.trim().ifBlank {
                    snapshot.plan.selectedTrackerId.trim()
                }
            }
        }
    }

    private fun trailWithLocalRuntimeOverlay(
        runtime: TrackingRuntimeSnapshot,
        trackerId: String,
        currentTrail: List<com.geovault.tracker.db.QueuedLocation>,
        trailPointLimit: Int,
    ): List<com.geovault.tracker.db.QueuedLocation> {
        val point = localRuntimeOverlayPoint(runtime, trackerId) ?: return currentTrail
        val last = currentTrail.lastOrNull()
        val activeSessionStart = point.startTimestampMs
        val tailSession = last?.startTimestampMs
        val tailMatchesActiveSession = last != null &&
            tailSession != null &&
            activeSessionStart != null &&
            tailSession == activeSessionStart
        if (tailMatchesActiveSession && last.time >= point.time) {
            return currentTrail
        }
        return MapTrailEngine.fitToCount(
            currentTrail + point,
            trailPointLimit,
        )
    }

    private fun localRuntimeOverlayPoint(
        runtime: TrackingRuntimeSnapshot,
        trackerId: String,
    ): com.geovault.tracker.db.QueuedLocation? {
        val lat = runtime.lastTrackedLatitude ?: return null
        val lon = runtime.lastTrackedLongitude ?: return null
        val runtimeTs = runtime.lastTrackedTimestampMs
        if (runtimeTs <= 0L) return null
        val activeSessionStart = runtime.sessionStartTimeMs.takeIf { it > 0L }
        return com.geovault.tracker.db.QueuedLocation(
            id = 0L,
            trackerId = trackerId,
            time = runtimeTs,
            latitude = lat,
            longitude = lon,
            altitude = null,
            speed = null,
            bearing = null,
            accuracy = runtime.lastAccuracyMeters,
            sat = null,
            prov = MapTrailEngine.PROVENANCE_LOCAL_GPS_RUNTIME,
            dist = null,
            startTimestampMs = activeSessionStart,
        )
    }

    private fun validLatLngsFromTrail(
        trail: List<com.geovault.tracker.db.QueuedLocation>,
    ): List<org.maplibre.android.geometry.LatLng> {
        return trail.mapNotNull { q ->
            if (com.geovault.common.maps.core.isValidMapLibreGeographicLatLng(q.latitude, q.longitude)) {
                org.maplibre.android.geometry.LatLng(q.latitude, q.longitude)
            } else {
                null
            }
        }
    }

    private fun markerDirectionDegrees(
        trail: List<com.geovault.tracker.db.QueuedLocation>,
        headLatitude: Double,
        headLongitude: Double,
    ): Float {
        val points = validLatLngsFromTrail(trail).toMutableList()
        val head = org.maplibre.android.geometry.LatLng(headLatitude, headLongitude)
        val last = points.lastOrNull()
        if (last == null || last.latitude != head.latitude || last.longitude != head.longitude) {
            points.add(head)
        }
        return trackDirectionDegrees(points)
    }

    private fun buildTrailLines(
        mode: TrackerMapDisplayMode,
        effectiveTrail: List<com.geovault.tracker.db.QueuedLocation>,
        allQueueTrailsByTracker: Map<String, List<com.geovault.tracker.db.QueuedLocation>>,
        trackerColorById: Map<String, String>,
        singleTrackerLineColorHex: String,
        runtime: TrackingRuntimeSnapshot,
    ): List<com.geovault.common.maps.render.MapRenderLine> {
        val maxTimeGapMs = maxTimeGapMsForRuntime(runtime)
        return if (
            (mode == TrackerMapDisplayMode.ALL_QUEUE || mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) &&
            allQueueTrailsByTracker.isNotEmpty()
        ) {
            buildAllQueueLines(allQueueTrailsByTracker, trackerColorById, maxTimeGapMs)
        } else {
            buildSegmentedLines(
                lineIdPrefix = "tracker-trail",
                points = effectiveTrail,
                lineColorHex = singleTrackerLineColorHex,
                maxTimeGapMs = maxTimeGapMs,
            )
        }
    }

    private fun buildAllQueueLines(
        allQueueTrailsByTracker: Map<String, List<com.geovault.tracker.db.QueuedLocation>>,
        trackerColorById: Map<String, String>,
        maxTimeGapMs: Long,
    ): List<com.geovault.common.maps.render.MapRenderLine> {
        return allQueueTrailsByTracker.entries
            .sortedBy { it.key }
            .flatMap { (trackerId, queuedLocations) ->
                val color = normalizeColor(trackerColorById[trackerId]) ?: GeoVaultColorTokens.Hex.Gray500
                buildSegmentedLines(
                    lineIdPrefix = "all-track-$trackerId",
                    points = queuedLocations,
                    lineColorHex = color,
                    maxTimeGapMs = maxTimeGapMs,
                )
            }
    }

    private fun buildSegmentedLines(
        lineIdPrefix: String,
        points: List<com.geovault.tracker.db.QueuedLocation>,
        lineColorHex: String,
        maxTimeGapMs: Long = MAX_TRACK_TIME_GAP_MS,
    ): List<com.geovault.common.maps.render.MapRenderLine> {
        if (points.isEmpty()) return emptyList()
        val sessionGroups = groupAdjacentBySession(points)
        val lines = mutableListOf<com.geovault.common.maps.render.MapRenderLine>()
        sessionGroups.forEachIndexed { sessionIndex, group ->
            val timeGroups = splitByTimeGap(group, maxTimeGapMs)
            timeGroups.forEachIndexed { timeIndex, timeGroup ->
                val coords = timeGroup.map {
                    com.geovault.common.geo.Wgs84Point(it.latitude, it.longitude)
                }
                val distanceSegments = com.geovault.common.maps.core.geoVaultSplitTrackByDistance(
                    coords,
                    MAX_TRACK_JUMP_METERS,
                )
                distanceSegments.forEachIndexed { distanceIndex, segment ->
                    lines += com.geovault.common.maps.render.MapRenderLine(
                        id = "$lineIdPrefix-$sessionIndex-$timeIndex-$distanceIndex",
                        coordinates = segment.map { it.latitude to it.longitude },
                        lineColorHex = lineColorHex,
                    )
                }
            }
        }
        return lines
    }

    private fun splitByTimeGap(
        points: List<com.geovault.tracker.db.QueuedLocation>,
        maxGapMs: Long,
    ): List<List<com.geovault.tracker.db.QueuedLocation>> {
        if (points.isEmpty()) return emptyList()
        if (points.size == 1) return listOf(points)
        val groups = mutableListOf<MutableList<com.geovault.tracker.db.QueuedLocation>>()
        var current = mutableListOf(points.first())
        for (index in 1 until points.size) {
            val point = points[index]
            val previous = points[index - 1]
            if (point.time - previous.time > maxGapMs) {
                groups += current
                current = mutableListOf()
            }
            current += point
        }
        groups += current
        return groups
    }

    private fun groupAdjacentBySession(
        points: List<com.geovault.tracker.db.QueuedLocation>,
    ): List<List<com.geovault.tracker.db.QueuedLocation>> {
        if (points.isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<com.geovault.tracker.db.QueuedLocation>>()
        var currentKey: Long? = points.first().startTimestampMs
        var currentGroup = mutableListOf<com.geovault.tracker.db.QueuedLocation>().apply { add(points.first()) }
        for (i in 1 until points.size) {
            val point = points[i]
            if (point.startTimestampMs == currentKey) {
                currentGroup.add(point)
            } else {
                groups.add(currentGroup)
                currentKey = point.startTimestampMs
                currentGroup = mutableListOf(point)
            }
        }
        groups.add(currentGroup)
        return groups
    }

    private fun normalizeColor(raw: String?): String? =
        com.geovault.common.ui.theme.GeoVaultColorHex.normalizeHashPrefix(raw)

    private fun trackDirectionDegrees(points: List<org.maplibre.android.geometry.LatLng>): Float {
        val validPoints = points.filter(::isValidPoint)
        if (validPoints.size < 2) return 0f
        val last = validPoints.last()
        for (i in validPoints.size - 2 downTo 0) {
            val prev = validPoints[i]
            val dLon = last.longitude - prev.longitude
            val dLat = last.latitude - prev.latitude
            if (dLon != 0.0 || dLat != 0.0) {
                return (Math.atan2(dLon, dLat) * 180.0 / Math.PI).toFloat()
            }
        }
        return 0f
    }

    private fun isValidPoint(point: org.maplibre.android.geometry.LatLng): Boolean {
        return point.latitude.isFinite() &&
            point.longitude.isFinite() &&
            point.latitude in -90.0..90.0 &&
            point.longitude in -180.0..180.0
    }

    private fun singleMarkerTitle(
        displayedTrackerId: String,
        displayedTrackerName: String,
        runtime: TrackingRuntimeSnapshot,
        trackerDisplayNameById: Map<String, String>,
    ): String? {
        val displayedId = displayedTrackerId.trim()
        return trackerDisplayNameById[displayedId]?.trim()?.takeIf { it.isNotEmpty() }
            ?: displayedTrackerName.trim().takeIf { it.isNotEmpty() }
            ?: runtime.selectedTrackerName.takeIf {
                displayedId.isEmpty() || displayedId == runtime.selectedTrackerId.trim()
            }?.trim()?.takeIf { it.isNotEmpty() }
            ?: displayedId.takeIf { it.isNotEmpty() }
    }

    private fun buildAccuracyPolygons(
        markers: List<com.geovault.common.maps.render.MapRenderPoint>,
        markerColorById: Map<String, String>,
        fallbackAccuracyMeters: Float?,
        allowAccuracyFallback: Boolean,
        sourceAccuracyByMarkerId: Map<String, Float>,
        fallbackAccuracyByTrackerId: Map<String, Float>,
        allowAccuracyFallbackByTrackerId: Set<String>,
        defaultSingleTrackerId: String,
    ): List<com.geovault.common.maps.render.MapRenderPolygon> {
        val accuracyInputs = markers.map { marker ->
            val trackerId = trackerIdForMarker(marker.id, defaultSingleTrackerId)
            val fallback = fallbackAccuracyByTrackerId[trackerId] ?: fallbackAccuracyMeters
            val allowFallback = trackerId in allowAccuracyFallbackByTrackerId || allowAccuracyFallback
            com.geovault.tracker.presentation.TrackerAccuracyCircleInput(
                polygonId = polygonIdForMarker(marker.id, trackerId),
                trackerId = trackerId,
                centerLatitude = marker.latitude,
                centerLongitude = marker.longitude,
                sourceAccuracyMeters = sourceAccuracyByMarkerId[marker.id],
                fallbackAccuracyMeters = fallback,
                allowFallback = allowFallback,
                colorHex = markerColorById[marker.id]
                    ?: com.geovault.tracker.presentation.TrackerMapIconIds.DEFAULT_COLOR_HEX,
            )
        }
        return accuracyCircleResolver.buildPolygons(accuracyInputs)
    }

    private data class TrackerMarkerRenderFeature(
        val marker: com.geovault.common.maps.render.MapRenderPoint,
        val sourceAccuracyMeters: Float?,
    )

    private fun buildMarkerColorById(
        mode: TrackerMapDisplayMode,
        markers: List<com.geovault.common.maps.render.MapRenderPoint>,
        trackerColorById: Map<String, String>,
        singleLineColorHex: String,
    ): Map<String, String> {
        return markers.associate { marker ->
            val color = when (mode) {
                TrackerMapDisplayMode.SINGLE_SESSION -> singleLineColorHex
                TrackerMapDisplayMode.ALL_QUEUE,
                TrackerMapDisplayMode.GROUP_PLACEHOLDER -> {
                    val trackerId = marker.id.removePrefix("remote-").trim()
                    normalizeColor(trackerColorById[trackerId]) ?: GeoVaultColorTokens.Hex.Gray500
                }
            }
            marker.id to color
        }
    }

    private fun trackerIdForMarker(markerId: String, defaultSingleTrackerId: String): String {
        return if (markerId == "last-fix") {
            defaultSingleTrackerId
        } else {
            markerId.removePrefix("remote-").trim()
        }
    }

    private fun polygonIdForMarker(markerId: String, trackerId: String): String {
        return if (markerId == "last-fix") {
            "accuracy-last-fix"
        } else {
            "accuracy-${trackerId.ifEmpty { markerId }}"
        }
    }

}
