package com.geovault.tracker.ui

import android.location.Location
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.geovault.common.ui.GeoVaultKeepScreenOn
import com.geovault.common.ui.time.rememberNowMs
import com.geovault.common.maps.core.GeoVaultMainMap
import com.geovault.common.maps.core.GeoVaultMainMapView
import com.geovault.common.maps.core.GeoVaultMapPaddingDp
import com.geovault.common.maps.core.GeoVaultMapPhase
import com.geovault.common.maps.core.MapLibreManager
import com.geovault.common.maps.core.animateCameraToFitLatLngBounds
import com.geovault.common.maps.core.animateCameraToHomeFit
import com.geovault.common.maps.core.geoVaultCenterCameraWithMinimumZoom
import com.geovault.common.maps.core.geoVaultCenterCameraPreserveZoom
import com.geovault.common.maps.core.moveCameraToFitLatLngBounds
import com.geovault.common.maps.core.geoVaultResetCameraBearingAndTilt
import com.geovault.common.maps.location.geoVaultMapHasFineOrCoarseLocation
import com.geovault.common.maps.location.rememberGeoVaultMapLocationPermissionState
import com.geovault.common.maps.location.rememberGeoVaultMapUserLocationPlugin
import com.geovault.common.maps.ui.camera.GeoVaultMapCameraInteractionEffect
import com.geovault.common.maps.ui.lifecycle.GeoVaultMapUserLocationNavigationLifecycle
import com.geovault.common.maps.render.GeoJsonRenderConfig
import com.geovault.common.maps.render.GeoJsonRenderPlugin
import com.geovault.common.maps.render.GeoVaultRenderedMapHitKind
import com.geovault.common.maps.ui.GeoVaultMapBottomActionPanel
import com.geovault.common.maps.ui.GeoVaultMapFabColumn
import com.geovault.common.maps.ui.GeoVaultMapInitialFrameShield
import com.geovault.common.maps.ui.GeoVaultMapLocationPrimeEffect
import com.geovault.common.maps.ui.GeoVaultMapFabIcon
import com.geovault.common.maps.ui.buildGeoVaultMapFabActions
import com.geovault.common.maps.ui.geoVaultLayerToggleFabAction
import com.geovault.common.maps.ui.geoVaultZoomInFabAction
import com.geovault.common.maps.ui.geoVaultZoomOutFabAction
import com.geovault.common.maps.ui.oneshot.rememberGeoVaultGpsOneShotMyLocationFabAction
import com.geovault.common.maps.ui.scale.GeoVaultMapScaleBar
import com.geovault.common.maps.ui.scale.GeoVaultMapScaleBarDefaults
import com.geovault.common.maps.ui.scaffold.GeoVaultMapScaffold
import com.geovault.common.geo.CoordinateFormat
import com.geovault.common.util.ClipboardCopyHelper
import com.geovault.common.ui.GeoVaultAuthShellState
import com.geovault.common.ui.GeoVaultTabShell
import com.geovault.common.ui.components.GeoVaultClickableWithTooltip
import com.geovault.common.ui.components.GeoVaultIconButton
import com.geovault.common.ui.components.GeoVaultLoadingSpinner
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.theme.geoVaultContentSecondaryColor
import com.geovault.tracker.map.MapRenderMath
import com.geovault.tracker.map.MapSessionDocument
import com.geovault.tracker.map.MapSessionEngine
import com.geovault.tracker.policy.ActiveButDeadTrackerPolicy
import com.geovault.tracker.Tracker
import com.geovault.tracker.params.TrackerParamsRouteArgs
import com.geovault.tracker.params.toTrackerParamsRouteArgs
import com.geovault.tracker.R
import com.geovault.tracker.presentation.TrackerMapDisplayMode
import com.geovault.tracker.presentation.TrackerMapFitTrailMode
import com.geovault.tracker.presentation.TrackerMapRenderContract
import com.geovault.tracker.presentation.TrackerMapSelectionCard
import com.geovault.tracker.presentation.TrackerMapTopLeftChipUiModel
import com.geovault.tracker.presentation.TrackerMapLockFabBehavior
import com.geovault.tracker.presentation.TrackerMapUserLocationInput
import com.geovault.tracker.presentation.TrackerMapViewModel
import com.geovault.tracker.ui.time.mapElapsedAgoText
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
private const val RENDER_COALESCE_MS = 120L

@Composable
fun MapScreen(
    map: GeoVaultMainMap,
    mapViewModel: TrackerMapViewModel,
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    auth: GeoVaultAuthShellState,
    isServerAccessible: Boolean,
    onNavigate: (MapNavigation) -> Unit,
) {
    GeoVaultTabShell(
        title = stringResource(R.string.map_screen_title),
        auth = auth,
        modifier = modifier.fillMaxSize(),
        settingsOverflowTooltip = stringResource(R.string.tooltip_nav_settings),
        scrollAuthenticatedMainContent = false,
        authenticatedContentHorizontalPadding = 0.dp,
        authenticatedBottomSpacer = 0.dp,
        authenticatedMainContent = {
            TrackerMapAuthenticatedContent(
                map = map,
                viewModel = mapViewModel,
                isActive = isActive,
                isServerAccessible = isServerAccessible,
                onNavigate = onNavigate,
            )
        },
        tabOverlay = { TrackerParamsOverlayLayer() },
    )
}

@Composable
private fun TrackerMapAuthenticatedContent(
    map: GeoVaultMainMap,
    viewModel: TrackerMapViewModel,
    isActive: Boolean,
    isServerAccessible: Boolean,
    onNavigate: (MapNavigation) -> Unit,
) {
    val renderPackage by viewModel.renderPackage.collectAsState()
    val chrome by viewModel.chrome.collectAsState()
    val sessionDocument by viewModel.sessionDocument.collectAsState()
    val trailView by viewModel.trailView.collectAsState()
    val recording = chrome.recording
    val mapPaddingPolicy = MapRenderMath
    val topLeftChipModel = chrome.chip
    val selectionModel = sessionDocument.toSelectionPanelUiModel()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapLocationPermission = rememberGeoVaultMapLocationPermissionState()
    val locationPermission by mapLocationPermission

    DisposableEffect(viewModel, isActive, lifecycleOwner) {
        if (isActive) {
            viewModel.onMapSurfaceVisible()
        } else {
            viewModel.onMapSurfaceHidden(
                markBackground = !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            )
        }
        onDispose {
            if (isActive) {
                viewModel.onMapSurfaceHidden(
                    markBackground = !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                )
            }
        }
    }

    var isLifecycleStarted by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    var renderResumeEpoch by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            isLifecycleStarted = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.onHostResumed()
                    renderResumeEpoch += 1
                }
                Lifecycle.Event.ON_PAUSE -> {
                    viewModel.onHostPaused()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val shouldKeepScreenOn = isActive && chrome.keepScreenOnWhileViewingMap
    GeoVaultKeepScreenOn(enabled = shouldKeepScreenOn)

    val density = LocalDensity.current
    // MEASURED-NOT-GUESSED CHIP RESERVE: the top-left chip's height varies with its content
    // (name-only vs. name+status vs. name+user-label+status), so a fixed dp guess for how much
    // top viewport to reserve during a bounds fit either wastes space or -- worse -- undershoots
    // the tallest variant and lets a fitted marker land behind the chip. Track the chip's actual
    // rendered height (see the `onGloballyPositioned` on its Box below) and feed that into the
    // bounds-fit padding instead. Falls back to the policy's static guess for the first frame(s)
    // before layout has run, and to zero when no chip is shown at all.
    var topLeftChipMeasuredHeightPx by remember { mutableStateOf(0) }
    var selectionPanelMeasuredHeightPx by remember { mutableStateOf(0) }
    val topLeftChipReserveDp = when {
        topLeftChipModel !is TrackerMapTopLeftChipUiModel.Visible -> 0.dp
        topLeftChipMeasuredHeightPx > 0 -> with(density) { topLeftChipMeasuredHeightPx.toDp() }
        else -> MapRenderMath.FallbackTopLeftChipViewportReserveTopDp
    }
    val selectionPanelReserveDp = when {
        selectionModel == null -> 0.dp
        selectionPanelMeasuredHeightPx > 0 -> with(density) { selectionPanelMeasuredHeightPx.toDp() }
        else -> MapRenderMath.FallbackSelectionPanelViewportReserveBottomDp
    }
    val boundsFitPaddingPx = remember(
        density,
        mapPaddingPolicy,
        topLeftChipReserveDp,
        selectionPanelReserveDp,
    ) {
        mapPaddingPolicy.computeBoundsFitPaddingPx(
            density,
            topLeftChipReserveDp,
            selectionPanelReserveDp,
        )
    }
    val renderPlugin = remember {
        GeoJsonRenderPlugin(
            sourceIdPrefix = TrackerMapRenderContract.SOURCE_ID_PREFIX,
            config = GeoJsonRenderConfig(
                synchronousGeoJsonApplication = true,
                showPointCircles = false,
                showPointLabelsAndIcons = true,
                showPointTextLabels = false,
                renderPointSymbolsAboveLines = true,
                useSynchronousSourceUpdates = true,
                defaultIconSize = 0.75f,
                showPolygonOutline = false,
                defaultPolygonFillOpacity = 1f,
            ),
            context = context,
        )
    }
    renderPlugin.renderedMapTapHitKinds = setOf(GeoVaultRenderedMapHitKind.Point)
    renderPlugin.onRenderedMapHitSelected = { hit ->
        val trackerId = trackerIdFromRenderedHit(
            id = hit.id,
            displayedTrackerId = sessionDocument.displayedTrackerId,
            selectedTrackerId = sessionDocument.selectedTrackerId,
        )
        if (trackerId != null) {
            viewModel.onTrackerMarkerTapped(trackerId)
            true
        } else {
            false
        }
    }
    renderPlugin.onRenderedMapBackgroundTapped = {
        viewModel.onMapBackgroundTapped()
    }
    val markerIconPlugin = remember(context) {
        TrackerMapMarkerIconPlugin(context.applicationContext)
    }
    val locationPlugin = rememberGeoVaultMapUserLocationPlugin(context = context)
    val viewportContextSeed = remember(
        sessionDocument.mode,
        sessionDocument.groupId,
        sessionDocument.displayedTrackerId,
        sessionDocument.selectedTrackerId,
    ) {
        val effectiveDisplayedTrackerId = sessionDocument.displayedTrackerId
            .ifBlank { sessionDocument.selectedTrackerId }
            .trim()
        "${sessionDocument.mode}|${sessionDocument.groupId.trim()}|$effectiveDisplayedTrackerId"
    }
    val liveGpsPuckRequestedThisSession = sessionDocument.surface.liveGpsPuckRequested
    val clearMapLocks = remember(viewModel) {
        { viewModel.disableAllMapLocks() }
    }
    var didInitialBounds by remember { mutableStateOf(false) }
    // INITIAL-FRAME GATE: covers the map view with a loading overlay until the very
    // first camera directive at this viewport context has been applied (or, as a
    // safety net, until a short timeout has elapsed). The overlay stays until
    // `onMapReady` has run and the camera is positioned. Without this, the user briefly
    // sees the map at MapLibre's default camera (around 0,0) for the time it takes
    // the directive `LaunchedEffect` to schedule + run after `phase` flips to Ready.
    var mapInitialFrameReady by remember { mutableStateOf(false) }
    LaunchedEffect(viewportContextSeed) {
        didInitialBounds = false
        // Re-arm the loading overlay on every viewport context change so the brief
        // window between "old context's camera position" and "new context's camera
        // fit" is hidden (e.g. switching tracker, switching to group mode).
        mapInitialFrameReady = false
        // A taller/shorter chip from the previous viewport must not feed stale padding into the
        // first bounds fit for this one -- fall back to the default reserve until this
        // viewport's own chip (if any) reports its measured height.
        topLeftChipMeasuredHeightPx = 0
        selectionPanelMeasuredHeightPx = 0
    }
    val displayedTrackerId = sessionDocument.displayedTrackerId.trim()
        .ifBlank { sessionDocument.selectedTrackerId.trim() }
    val locallyRecordedTrackerId = recording.locallyRecordedTrackerId.trim()
    val layerFabAction = remember(map) { geoVaultLayerToggleFabAction(map) }
    val zoomInFabAction = remember(map) { geoVaultZoomInFabAction(map) }
    val zoomOutFabAction = remember(map) { geoVaultZoomOutFabAction(map) }
    val gpsFabAction = key(viewportContextSeed, chrome.userLocation.shouldEnablePuck) {
        rememberGeoVaultGpsOneShotMyLocationFabAction(
            map = map,
            userLocation = locationPlugin,
            order = 30,
            onLocationResolved = { latLng ->
                viewModel.setGpsHomeAnchor(latLng.latitude, latLng.longitude)
                mapLocationPermission.value = context.geoVaultMapHasFineOrCoarseLocation()
            },
            showUserLocationPuck = chrome.userLocation.shouldEnablePuck,
            coordinateOverride = {
                val recordedId = recording.locallyRecordedTrackerId.trim()
                if (recording.localRecordingActive &&
                    recordedId.isNotEmpty() &&
                    displayedTrackerId == recordedId
                ) {
                    val lat = recording.lastTrackedLatitude
                    val lon = recording.lastTrackedLongitude
                    if (lat != null && lon != null) LatLng(lat, lon) else null
                } else {
                    null
                }
            },
        )
    }

    DisposableEffect(locationPlugin) {
        val listener: (Location) -> Unit = { location ->
            viewModel.setFollowPuck(location.latitude, location.longitude)
        }
        locationPlugin.addLocationListener(listener)
        onDispose { locationPlugin.removeLocationListener(listener) }
    }

    DisposableEffect(map) {
        // App-specific marker images must be in the style before GeoJsonRenderPlugin reapplies
        // its current render state during basemap reloads.
        map.registerPlugin(markerIconPlugin)
        map.registerPlugin(renderPlugin)
        map.registerPlugin(locationPlugin)
        onDispose {
            renderPlugin.onRenderedMapHitSelected = null
            renderPlugin.onRenderedMapBackgroundTapped = null
            map.unregisterPlugin(locationPlugin)
            map.unregisterPlugin(renderPlugin)
            map.unregisterPlugin(markerIconPlugin)
        }
    }

    val fabDescSource = stringResource(R.string.map_fab_toggle_source)
    val fabDescFitTrail = stringResource(R.string.map_fab_fit_trail)
    val fabDescLiveGpsPuck = stringResource(R.string.map_fab_live_gps_puck)
    val fabDescFollow = stringResource(R.string.map_fab_follow_lock)
    val fabDescZoomIn = stringResource(R.string.map_fab_zoom_in)
    val fabDescZoomOut = stringResource(R.string.map_fab_zoom_out)
    val fabDescLockSelection = stringResource(R.string.map_action_lock_selection)
    val fabDescUnlockSelection = stringResource(R.string.map_action_unlock_selection)
    val fabDescLiveActiveFitEnable = stringResource(R.string.live_active_fit_enable)
    val fabDescLiveActiveFitDisable = stringResource(R.string.live_active_fit_disable)
    val tooltipMapLayers = stringResource(R.string.tooltip_map_layers)
    val tooltipMapZoomLatest = stringResource(R.string.tooltip_map_zoom_latest)
    val tooltipMapLiveGpsPuck = stringResource(R.string.tooltip_map_live_gps_puck)
    val tooltipMapZoomIn = stringResource(R.string.tooltip_map_zoom_in)
    val tooltipMapZoomOut = stringResource(R.string.tooltip_map_zoom_out)
    val tooltipMapLiveActiveFit = stringResource(R.string.tooltip_map_live_active_fit)
    val tooltipMapSelectionZoomLock = stringResource(R.string.tooltip_map_selection_zoom_lock)

    val phase by map.phase.collectAsState()
    LaunchedEffect(phase, isActive) {
        viewModel.setMapReady(isActive && phase == GeoVaultMapPhase.Ready)
    }

    val locationSessionActive = isLifecycleStarted && isActive
    LaunchedEffect(
        locationSessionActive,
        locationPermission,
        phase,
        liveGpsPuckRequestedThisSession,
        displayedTrackerId,
        locallyRecordedTrackerId,
    ) {
        viewModel.updateLocationSurface(
            TrackerMapUserLocationInput(
                isMapActive = locationSessionActive,
                hasLocationPermission = locationPermission,
                isMapReady = phase == GeoVaultMapPhase.Ready,
                userLocationRequestedThisSession = liveGpsPuckRequestedThisSession,
                displayedTrackerId = displayedTrackerId,
                locallyRecordedTrackerId = locallyRecordedTrackerId,
            )
        )
    }
    val userLocationDecision = chrome.userLocation
    val useTrackingLocationFixes = recording.localRecordingActive &&
        userLocationDecision.shouldStreamGps

    GeoVaultMapUserLocationNavigationLifecycle(
        userLocation = locationPlugin,
        shouldStreamGps = userLocationDecision.shouldStreamGps && !useTrackingLocationFixes,
        shouldEnablePuck = userLocationDecision.shouldEnablePuck,
        showAccuracyCircle = remember(locationPlugin) { locationPlugin.isAccuracyCircleVisible() },
        gpsIntervalMs = 2000L,
    )
    LaunchedEffect(phase, userLocationDecision.shouldEnablePuck) {
        if (phase != GeoVaultMapPhase.Ready) return@LaunchedEffect
        locationPlugin.setCameraTracking(false)
    }
    GeoVaultMapLocationPrimeEffect(
        location = locationPlugin,
        shouldStreamGps = userLocationDecision.shouldStreamGps && !useTrackingLocationFixes,
        providerName = "tracker-map-prime",
    )
    LaunchedEffect(
        useTrackingLocationFixes,
        recording.lastTrackedLatitude,
        recording.lastTrackedLongitude,
        recording.lastTrackedTimestampMs,
    ) {
        if (!useTrackingLocationFixes) return@LaunchedEffect
        val lat = recording.lastTrackedLatitude ?: return@LaunchedEffect
        val lon = recording.lastTrackedLongitude ?: return@LaunchedEffect
        val synthetic = Location("tracker-recording").apply {
            latitude = lat
            longitude = lon
            accuracy = recording.lastAccuracyMeters ?: 12f
            time = recording.lastTrackedTimestampMs.takeIf { it > 0L }
                ?: System.currentTimeMillis()
        }
        locationPlugin.renderLocation(synthetic)
    }

    GeoVaultMapCameraInteractionEffect(
        map = map,
        onCameraTakeover = { viewModel.disableAllMapLocks() },
        onUserOwnedZoom = { viewModel.onUserOwnedZoom() },
    )
    LaunchedEffect(
        phase,
        renderPackage.revision,
        sessionDocument.mode,
        sessionDocument.visibleTrackerIds,
        trailView.tracksByTrackerId.keys,
    ) {
        if (phase != GeoVaultMapPhase.Ready) return@LaunchedEffect
        delay(RENDER_COALESCE_MS)
        val resolvedState = markerIconPlugin.prepareForRender(renderPackage.renderState)
        renderPlugin.setRenderState(resolvedState)
    }
    LaunchedEffect(phase, renderResumeEpoch) {
        if (phase != GeoVaultMapPhase.Ready) return@LaunchedEffect
        val resolvedState = markerIconPlugin.prepareForRender(renderPackage.renderState)
        renderPlugin.setRenderState(resolvedState)
    }

    // CAMERA-DIRECTIVE: single consumer for every VM-resolved camera move -- precedence-driven
    // (SelectionLock/FollowLock/LiveActiveFit/InitialFit) and one-shot explicit (ExplicitFit)
    // directives both flow through this one stream, each stamped with the manual-control
    // generation active at mint time. Discarding a directive whose generation is behind the
    // ViewModel's *current* generation is what closes the "fit landed while panning" race: a
    // directive minted a moment before a gesture can still reach this effect, but by the time it
    // runs, the generation check catches that the user has since taken over the camera and skips
    // applying it. Precedence itself is enforced upstream so this effect doesn't have to reason
    // about which lock "wins"; it just applies whatever the VM resolved. Tracks consumed
    // directive ids for InitialFit so the one-shot semantics survive bounds shape churn (e.g.
    // trail growth) until the viewport context resets and re-arms them.
    val cameraDirective by viewModel.cameraDirective.collectAsState()
    // LIVE-GENERATION KEY: `cameraGeneration` and `viewportContextSeed` are both included
    // alongside `cameraDirective.id` so a user gesture or a viewport switch that lands between
    // this effect being scheduled and actually running cancels the stale run outright, rather
    // than relying solely on the one-time staleness check at effect entry.
    val cameraGeneration by viewModel.cameraGenerationFlow.collectAsState()
    LaunchedEffect(phase, cameraDirective.id, cameraGeneration, viewportContextSeed) {
        if (phase != GeoVaultMapPhase.Ready) return@LaunchedEffect
        val directive = cameraDirective
        if (directive.generation != viewModel.cameraGeneration()) return@LaunchedEffect
        // INITIAL-FRAME SHIELD: only a directive that actually positions the camera may lift the
        // loading overlay. A `None` directive (nothing resolvable yet for this viewport -- e.g.
        // bounds/lock target still loading) must leave the overlay up; flipping it here used to
        // briefly reveal the map at MapLibre's stale/default camera position.
        when (directive) {
            is com.geovault.tracker.presentation.TrackerMapCameraDirective.None -> Unit
            is com.geovault.tracker.presentation.TrackerMapCameraDirective.CenterOnPoint -> {
                if (directive.reason ==
                    com.geovault.tracker.presentation.TrackerMapCameraDirective.Reason.LiveActiveFit
                ) {
                    geoVaultCenterCameraPreserveZoom(
                        map = map,
                        latitude = directive.latitude,
                        longitude = directive.longitude,
                    )
                } else {
                    // FOCUS-NOT-PRESERVE: zoom in to a sensible floor instead of leaving the camera at
                    // whatever zoom a prior fit happened to land on -- e.g. a selection lock engaging
                    // the instant a stream starts should focus on the tracker's position, not inherit
                    // a leftover full-extent zoom level. Never zooms back out past a closer zoom the
                    // user (or a prior directive) already set.
                    geoVaultCenterCameraWithMinimumZoom(
                        map = map,
                        latitude = directive.latitude,
                        longitude = directive.longitude,
                        minimumZoom = MapLibreManager.DEFAULT_POINT_ZOOM,
                    )
                }
                // Order matters: the camera move above must complete BEFORE we flip the
                // overlay flag, otherwise we'd reveal the map for one frame at the previous
                // (default / stale) camera position. The MapLibre move is synchronous so by
                // the time we reach this line the new camera is in the next frame.
                mapInitialFrameReady = true
            }
            is com.geovault.tracker.presentation.TrackerMapCameraDirective.FitBounds -> {
                if (directive.reason == com.geovault.tracker.presentation.TrackerMapCameraDirective.Reason.InitialFit) {
                    if (didInitialBounds) {
                        mapInitialFrameReady = true
                        return@LaunchedEffect
                    }
                    fitTrackerMapBounds(
                        map = map,
                        bounds = directive.bounds,
                        boundsFitPaddingPx = boundsFitPaddingPx,
                        mode = directive.mode,
                    )
                    didInitialBounds = true
                } else {
                    if (directive.reason ==
                        com.geovault.tracker.presentation.TrackerMapCameraDirective.Reason.ExplicitFit &&
                        directive.mode == TrackerMapFitTrailMode.Animated
                    ) {
                        map.animateCameraToHomeFit(
                            bounds = directive.bounds,
                            gpsAnchor = null,
                            paddingPx = boundsFitPaddingPx,
                        )
                    } else {
                        fitTrackerMapBounds(
                            map = map,
                            bounds = directive.bounds,
                            boundsFitPaddingPx = boundsFitPaddingPx,
                            mode = directive.mode,
                        )
                    }
                }
                mapInitialFrameReady = true
            }
        }
    }
    // SAFETY NET: if no camera directive ever arrives with bounds (e.g. fresh install
    // with empty queue and the geometry endpoint is slow), don't leave the map hidden
    // forever. Wait a short, fixed window after `phase == Ready` for the directive
    // path to land naturally; if it doesn't, reveal the map anyway. The user may
    // briefly see a default camera position, but that's strictly better than an
    // indefinite spinner.
    LaunchedEffect(phase, viewportContextSeed) {
        if (phase != GeoVaultMapPhase.Ready) return@LaunchedEffect
        if (mapInitialFrameReady) return@LaunchedEffect
        delay(800L)
        mapInitialFrameReady = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background),
    ) {
        // Rendered above the map (pushes it down slightly) rather than overlaid, so it never
        // has to fight the top-left tracker chip or the top-right FAB column for screen space --
        // both of those already claim the top edge of the map surface itself.
        MapBatteryOptimizationHint(
            visible = sessionDocument.surface.batteryOptimizationHintVisible,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        MapTrailDegradeHint(
            visible = chrome.trailDegradedVisible,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val lockFabBehavior = chrome.lockFab
            val lockFabIsActive = when (lockFabBehavior) {
                is TrackerMapLockFabBehavior.SelectionLock -> lockFabBehavior.isLocked
                is TrackerMapLockFabBehavior.LiveActiveFit -> lockFabBehavior.isEnabled
                is TrackerMapLockFabBehavior.FollowLock -> lockFabBehavior.isEnabled
            }
            val mapFabActions = buildGeoVaultMapFabActions {
                action(
                    id = "source",
                    order = 10,
                    icon = layerFabAction.icon,
                    contentDescription = fabDescSource,
                    onTap = layerFabAction.onTap,
                    tooltip = tooltipMapLayers,
                )
                action(
                    id = "home_extent",
                    order = 20,
                    icon = GeoVaultMapFabIcon.Vector(Icons.Default.Home),
                    contentDescription = fabDescFitTrail,
                    tooltip = tooltipMapZoomLatest,
                    onTap = {
                        clearMapLocks()
                        if (phase == GeoVaultMapPhase.Ready) {
                            viewModel.requestFitTrail()
                        }
                    },
                )
                if (chrome.showMyLocationFab) {
                    action(
                        id = gpsFabAction.id,
                        order = gpsFabAction.order,
                        icon = gpsFabAction.icon,
                        contentDescription = fabDescLiveGpsPuck,
                        tooltip = tooltipMapLiveGpsPuck,
                        onTap = {
                            viewModel.requestLiveGpsPuck()
                            gpsFabAction.onTap?.invoke()
                        },
                    )
                }
                action(
                    id = "follow_lock",
                    order = 25,
                    icon = GeoVaultMapFabIcon.Vector(
                        if (lockFabIsActive) Icons.Default.Lock else Icons.Outlined.LockOpen,
                    ),
                    contentDescription = when (val behavior = lockFabBehavior) {
                        is TrackerMapLockFabBehavior.SelectionLock ->
                            if (behavior.isLocked) fabDescUnlockSelection else fabDescLockSelection
                        is TrackerMapLockFabBehavior.LiveActiveFit ->
                            if (behavior.isEnabled) fabDescLiveActiveFitDisable else fabDescLiveActiveFitEnable
                        is TrackerMapLockFabBehavior.FollowLock -> fabDescFollow
                    },
                    tooltip = when (lockFabBehavior) {
                        is TrackerMapLockFabBehavior.LiveActiveFit -> tooltipMapLiveActiveFit
                        is TrackerMapLockFabBehavior.SelectionLock,
                        is TrackerMapLockFabBehavior.FollowLock -> tooltipMapSelectionZoomLock
                    },
                    onTap = {
                        when (val behavior = lockFabBehavior) {
                            is TrackerMapLockFabBehavior.SelectionLock ->
                                viewModel.toggleDisplayedTrackerLock()
                            is TrackerMapLockFabBehavior.LiveActiveFit -> {
                                viewModel.setLiveActiveFit(!behavior.isEnabled)
                            }
                            is TrackerMapLockFabBehavior.FollowLock -> {
                                val nextEnabled = !behavior.isEnabled
                                viewModel.setFollowLock(nextEnabled)
                            }
                        }
                    },
                )
                if (chrome.liveActiveFit.showButton) {
                    action(
                        id = "live_active_fit",
                        order = 32,
                        icon = GeoVaultMapFabIcon.Drawable(
                            if (chrome.liveActiveFitEnabled) R.drawable.ic_live_active_fit_on
                            else R.drawable.ic_live_active_fit_off
                        ),
                        contentDescription = if (chrome.liveActiveFitEnabled) {
                            fabDescLiveActiveFitDisable
                        } else {
                            fabDescLiveActiveFitEnable
                        },
                        enabled = chrome.liveActiveFit.buttonEnabled,
                        tooltip = tooltipMapLiveActiveFit,
                        onTap = {
                            viewModel.setLiveActiveFit(!chrome.liveActiveFitEnabled)
                        },
                    )
                }
                action(
                    id = "zoom_in",
                    order = 40,
                    icon = zoomInFabAction.icon,
                    contentDescription = fabDescZoomIn,
                    tooltip = tooltipMapZoomIn,
                    onTap = {
                        if (phase == GeoVaultMapPhase.Ready) {
                            viewModel.onUserOwnedZoom()
                            zoomInFabAction.onTap?.invoke()
                        }
                    },
                )
                action(
                    id = "zoom_out",
                    order = 50,
                    icon = zoomOutFabAction.icon,
                    contentDescription = fabDescZoomOut,
                    tooltip = tooltipMapZoomOut,
                    onTap = {
                        if (phase == GeoVaultMapPhase.Ready) {
                            viewModel.onUserOwnedZoom()
                            zoomOutFabAction.onTap?.invoke()
                        }
                    },
                )
            }

            val gpsAccuracyIndicatorModel = chrome.gpsAccuracy
            GeoVaultMapScaffold(
                modifier = Modifier.fillMaxSize(),
                showDrawer = false,
                topStart = {
                    if (topLeftChipModel is TrackerMapTopLeftChipUiModel.Visible) {
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { coordinates ->
                                    topLeftChipMeasuredHeightPx = coordinates.size.height
                                }
                                .padding(top = 16.dp, start = 16.dp, end = 80.dp),
                        ) {
                            key(viewportContextSeed) {
                                MapTopLeftTrackerChip(
                                    modifier = Modifier.widthIn(max = maxWidth),
                                    model = topLeftChipModel,
                                    onCardClick = {
                                        onNavigate(
                                            MapNavigation.List(
                                                MapHostNavigationRequestResolver.fromListNavigationTarget(
                                                    viewModel.resolveListNavigationTarget()
                                                )
                                            )
                                        )
                                    },
                                    onResetClick = viewModel::restoreSelectedTrackerMapContext,
                                )
                            }
                        }
                    }
                },
                topEnd = {
                    GeoVaultMapFabColumn(
                        modifier = Modifier.padding(top = 16.dp, end = 16.dp),
                        actions = mapFabActions,
                    )
                },
                bottomStart = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.Start,
                    ) {
                        GeoVaultMapScaleBar(
                            map = map,
                            modifier = Modifier.padding(
                                start = GeoVaultMapScaleBarDefaults.EdgePadding,
                                bottom = if (selectionModel != null) {
                                    GeoVaultMapScaleBarDefaults.DrawerGap
                                } else {
                                    GeoVaultMapScaleBarDefaults.EdgePadding
                                },
                            ),
                        )
                        if (selectionModel != null) {
                            GeoVaultMapBottomActionPanel(
                                modifier = Modifier.onGloballyPositioned { coordinates ->
                                    selectionPanelMeasuredHeightPx = coordinates.size.height
                                },
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                    MapTrackerSelectionPanel(
                                        model = selectionModel,
                                        onViewInList = {
                                            onNavigate(
                                                MapNavigation.List(
                                                    MapHostNavigationRequestResolver.fromListNavigationTarget(
                                                        viewModel.resolveListNavigationTarget(selectionModel.trackerId)
                                                    )
                                                )
                                            )
                                        },
                                        onViewParams = {
                                            onNavigate(
                                                MapNavigation.Params(
                                                    selectionModel.toTrackerParamsRouteArgs(
                                                        viewModel.catalogTracker(selectionModel.trackerId),
                                                    )
                                                )
                                            )
                                        },
                                        onFocus = viewModel::focusSelectedTrackerOnMap,
                                        onToggleLock = viewModel::toggleSelectedTrackerLock,
                                        onClear = viewModel::clearMapTrackerSelection,
                                    )
                                }
                            }
                        }
                    }
                },
                bottomEnd = {
                    Column(
                        modifier = Modifier.padding(bottom = 16.dp, end = 16.dp),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (gpsAccuracyIndicatorModel.isVisible) {
                            MapGpsAccuracyIndicator()
                        }
                        MapStreamingIndicator(
                            model = chrome.streamingStatus,
                        )
                    }
                },
                mapContent = {
                    GeoVaultMainMapView(
                        modifier = Modifier.fillMaxSize(),
                        map = map,
                        showDefaultSourceToggle = false,
                        includeDefaultFabColumnPadding = false,
                        mapPaddingDp = GeoVaultMapPaddingDp(),
                        suppressMapLoadErrorDialog = !isServerAccessible,
                    )
                    if (sessionDocument.surface.geometryLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colors.background),
                            contentAlignment = Alignment.Center,
                        ) {
                            GeoVaultLoadingSpinner(
                                bottomText = stringResource(R.string.map_status_map_loading),
                            )
                        }
                    }
                },
            )
            GeoVaultMapInitialFrameShield(
                visible = !mapInitialFrameReady,
                statusText = stringResource(R.string.map_status_map_loading),
            )
        }
    }
}

private data class MapSelectionPanelUiModel(
    val trackerId: String,
    val trackerName: String,
    val isOwned: Boolean,
    val latitude: Double,
    val longitude: Double,
    val lastUpdatedMs: Long?,
    val serverMetadataUpdatedAtMs: Long?,
    val lastPointParamsMs: Long?,
    val accuracyMeters: Float?,
    val isLocked: Boolean,
    val showFocusAction: Boolean,
)

private fun fitTrackerMapBounds(
    map: GeoVaultMainMap,
    bounds: LatLngBounds,
    boundsFitPaddingPx: IntArray,
    mode: TrackerMapFitTrailMode,
) {
    geoVaultResetCameraBearingAndTilt(map)
    when (mode) {
        TrackerMapFitTrailMode.Animated -> {
            map.animateCameraToFitLatLngBounds(bounds, boundsFitPaddingPx)
        }
        TrackerMapFitTrailMode.Instant -> {
            map.moveCameraToFitLatLngBounds(bounds, boundsFitPaddingPx)
        }
    }
}

private fun MapSessionDocument.toSelectionPanelUiModel(): MapSelectionPanelUiModel? {
    val selection = selectionCard ?: return null
    if (!surface.bottomCardVisible) return null
    return MapSelectionPanelUiModel(
        trackerId = selection.trackerId,
        trackerName = selection.trackerName,
        isOwned = selection.isOwned,
        latitude = selection.latitude,
        longitude = selection.longitude,
        lastUpdatedMs = selection.lastUpdatedMs,
        serverMetadataUpdatedAtMs = selection.serverMetadataUpdatedAtMs,
        lastPointParamsMs = selection.lastPointParamsMs,
        accuracyMeters = selection.accuracyMeters,
        isLocked = cameraLock.selectionTrackerId == selection.trackerId,
        showFocusAction = MapSessionEngine.resolveFocusActionVisible(mode),
    )
}

private fun MapSelectionPanelUiModel.toTrackerParamsRouteArgs(
    tracker: Tracker? = null,
): TrackerParamsRouteArgs {
    return TrackerMapSelectionCard(
        trackerId = trackerId,
        trackerName = trackerName,
        latitude = latitude,
        longitude = longitude,
        lastUpdatedMs = lastUpdatedMs,
        accuracyMeters = accuracyMeters,
        isOwned = isOwned,
        serverMetadataUpdatedAtMs = serverMetadataUpdatedAtMs,
        lastPointParamsMs = lastPointParamsMs,
    ).toTrackerParamsRouteArgs(tracker)
}

@Composable
private fun MapTrackerSelectionPanel(
    model: MapSelectionPanelUiModel,
    onViewInList: () -> Unit,
    onViewParams: () -> Unit,
    onFocus: () -> Unit,
    onToggleLock: () -> Unit,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    val clipboardHelper = remember(context) { ClipboardCopyHelper(context) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colors.background,
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val nameText = model.trackerName.ifBlank { stringResource(R.string.select_tracker) }
                Text(
                    text = nameText,
                    style = MaterialTheme.typography.subtitle2,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
                GeoVaultIconButton(
                    onClick = onToggleLock,
                    modifier = Modifier.size(28.dp),
                    tooltip = stringResource(R.string.tooltip_map_selection_zoom_lock),
                ) {
                    Icon(
                        imageVector = if (model.isLocked) Icons.Default.Lock else Icons.Outlined.LockOpen,
                        contentDescription = if (model.isLocked) {
                            stringResource(R.string.map_action_unlock_selection)
                        } else {
                            stringResource(R.string.map_action_lock_selection)
                        },
                        tint = GeoVaultColorTokens.MainBlue,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(modifier = Modifier.size(8.dp))
                GeoVaultClickableWithTooltip(
                    onClick = onClear,
                    modifier = Modifier
                        .size(28.dp)
                        .background(
                            color = if (MaterialTheme.colors.isLight) {
                                GeoVaultColorTokens.BorderLight
                            } else {
                                GeoVaultColorTokens.MainBlue.copy(alpha = 0.22f)
                            },
                            shape = CircleShape,
                        ),
                    tooltip = stringResource(R.string.tooltip_map_selection_close),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.trackers_dialog_cancel),
                        tint = GeoVaultColorTokens.MainBlue,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            val latLon = CoordinateFormat.DECIMAL_4.formatLatLon(model.latitude, model.longitude)
            Text(
                text = latLon,
                style = MaterialTheme.typography.body2,
                color = geoVaultContentSecondaryColor(),
                modifier = Modifier.clickable {
                    clipboardHelper.copyText(latLon, label = "Coordinates")
                },
            )
            val nowMs by rememberNowMs()
            val lastUpdatedText = mapElapsedAgoText(model.lastUpdatedMs, nowMs)
            val warnStale = model.lastUpdatedMs != null &&
                ActiveButDeadTrackerPolicy.isActiveButDead(
                    nowMs = nowMs,
                    updatedAtMs = model.serverMetadataUpdatedAtMs,
                    lastDataMs = model.lastUpdatedMs,
                    lastParamsMs = model.lastPointParamsMs,
                )
            val lastUpdatedColor = if (warnStale) {
                GeoVaultColorTokens.Error
            } else {
                geoVaultContentSecondaryColor()
            }
            Text(
                text = lastUpdatedText,
                style = MaterialTheme.typography.caption,
                color = lastUpdatedColor,
            )
            Spacer(modifier = Modifier.height(4.dp))
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val iconButtons = mutableListOf<@Composable () -> Unit>()
                if (model.showFocusAction) {
                    iconButtons.add {
                        MapInfoActionIconButton(
                            onClick = onFocus,
                            tooltip = stringResource(R.string.tooltip_map_selection_focus),
                            icon = {
                                androidx.compose.foundation.Image(
                                    painter = painterResource(id = R.drawable.ic_focus_point_round),
                                    contentDescription = stringResource(R.string.map_action_focus_tracker),
                                    colorFilter = ColorFilter.tint(GeoVaultColorTokens.MainBlue),
                                    modifier = Modifier.size(22.dp),
                                )
                            },
                        )
                    }
                }
                iconButtons.add {
                    MapInfoActionIconButton(
                        onClick = onViewParams,
                        tooltip = stringResource(R.string.tooltip_map_selection_view_params),
                        icon = {
                            androidx.compose.foundation.Image(
                                painter = painterResource(id = R.drawable.ic_params),
                                contentDescription = stringResource(R.string.map_action_view_params),
                                colorFilter = ColorFilter.tint(GeoVaultColorTokens.MainBlue),
                                modifier = Modifier.size(22.dp),
                            )
                        },
                    )
                }
                iconButtons.add {
                    MapInfoActionIconButton(
                        onClick = onViewInList,
                        tooltip = stringResource(R.string.tooltip_map_selection_view_in_list),
                        icon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.List,
                                contentDescription = stringResource(R.string.map_action_view_in_list),
                                tint = GeoVaultColorTokens.MainBlue,
                                modifier = Modifier.size(22.dp),
                            )
                        },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    iconButtons.forEach { button ->
                        button()
                    }
                }
            }
        }
    }
}

@Composable
private fun MapInfoActionIconButton(
    onClick: () -> Unit,
    tooltip: String,
    icon: @Composable () -> Unit,
) {
    GeoVaultSecondaryButton(
        text = "",
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        fitToContent = true,
        centeredContent = icon,
        contentPadding = PaddingValues(0.dp),
        tooltip = tooltip,
    )
}

@Composable
private fun MapTrailDegradeHint(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colors.background,
    ) {
        Text(
            text = stringResource(R.string.map_status_trail_degraded),
            style = MaterialTheme.typography.caption,
            color = geoVaultContentSecondaryColor(),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }
}

private fun trackerIdFromRenderedHit(
    id: String,
    displayedTrackerId: String,
    selectedTrackerId: String,
): String? {
    return when {
        id == "last-fix" -> displayedTrackerId.ifBlank { selectedTrackerId }
        id.startsWith("remote-") -> id.removePrefix("remote-")
        else -> null
    }?.trim()?.takeIf { it.isNotEmpty() }
}

