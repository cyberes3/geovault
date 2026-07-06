package com.geovault.tracker.ui

import android.app.Activity
import android.location.Location
import android.view.WindowManager
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
import androidx.compose.material.Scaffold
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInteropFilter
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
import com.geovault.common.ui.time.rememberNowMs
import com.geovault.common.maps.core.GeoVaultMainMap
import com.geovault.common.maps.core.GeoVaultMainMapView
import com.geovault.common.maps.core.GeoVaultMapPaddingDp
import com.geovault.common.maps.core.GeoVaultMapPhase
import com.geovault.common.maps.core.MapLibreManager
import com.geovault.common.maps.core.animateCameraToFitLatLngBounds
import com.geovault.common.maps.core.geoVaultCenterCameraWithMinimumZoom
import com.geovault.common.maps.core.geoVaultCreateGestureMoveStartedListener
import com.geovault.common.maps.core.geoVaultLatLngBoundsUnion
import com.geovault.common.maps.core.latLngOrNull
import com.geovault.common.maps.core.moveCameraToFitLatLngBounds
import com.geovault.common.maps.core.geoVaultResetCameraBearingAndTilt
import com.geovault.common.maps.location.rememberGeoVaultMapUserLocationPlugin
import com.geovault.common.maps.render.GeoJsonRenderConfig
import com.geovault.common.maps.render.GeoJsonRenderPlugin
import com.geovault.common.maps.render.GeoVaultRenderedMapHitKind
import com.geovault.common.maps.ui.GeoVaultMapFabColumn
import com.geovault.common.maps.ui.GeoVaultMapFabIcon
import com.geovault.common.maps.ui.buildGeoVaultMapFabActions
import com.geovault.common.maps.ui.geoVaultLayerToggleFabAction
import com.geovault.common.maps.ui.geoVaultZoomInFabAction
import com.geovault.common.maps.ui.geoVaultZoomOutFabAction
import com.geovault.common.maps.ui.oneshot.rememberGeoVaultGpsOneShotMyLocationFabAction
import com.geovault.common.maps.ui.scale.GeoVaultMapScaleBar
import com.geovault.common.maps.ui.scale.GeoVaultMapScaleBarDefaults
import com.geovault.common.ClipboardCopyHelper
import com.geovault.common.ui.components.GeoVaultAuthGate
import com.geovault.common.ui.components.GeoVaultClickableWithTooltip
import com.geovault.common.ui.components.GeoVaultIconButton
import com.geovault.common.ui.components.GeoVaultLoadingSpinner
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.components.GeoVaultTopBarSettingsMenuAction
import com.geovault.common.ui.components.GeoVaultTopTitleBar
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.theme.geoVaultContentSecondaryColor
import com.geovault.common.ui.theme.geoVaultHairlineDividerColor
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.policy.ActiveButDeadTrackerPolicy
import com.geovault.tracker.params.TrackerParamsRouteArgs
import com.geovault.tracker.params.toTrackerParamsRouteArgs
import com.geovault.tracker.R
import com.geovault.tracker.location.TrackingPermissionGate
import com.geovault.tracker.presentation.LiveActiveFitInput
import com.geovault.tracker.presentation.TrackerMapDisplayMode
import com.geovault.tracker.presentation.TrackerMapFitTrailMode
import com.geovault.tracker.presentation.TrackerMapGpsAccuracyIndicatorPolicy
import com.geovault.tracker.presentation.TrackerMapLiveActiveFitPolicy
import com.geovault.tracker.presentation.TrackerMapRenderContract
import com.geovault.tracker.presentation.TrackerMapSelectionCard
import com.geovault.tracker.presentation.TrackerMapTopLeftChipMapper
import com.geovault.tracker.presentation.TrackerMapTopLeftChipUiModel
import com.geovault.tracker.presentation.TrackerMapUiState
import com.geovault.tracker.presentation.TrackerMapLockFabBehavior
import com.geovault.tracker.presentation.TrackerMapLockFabInput
import com.geovault.tracker.presentation.TrackerMapLockFabPolicy
import com.geovault.tracker.presentation.TrackerMapMyLocationFabPolicy
import com.geovault.tracker.presentation.TrackerMapUserLocationInput
import com.geovault.tracker.presentation.TrackerMapUserLocationPolicy
import com.geovault.tracker.presentation.TrackerMapViewModel
import com.geovault.tracker.ui.time.mapElapsedAgoText
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import java.util.Locale

private const val RENDER_COALESCE_MS = 120L

@Composable
fun MapScreen(
    map: GeoVaultMainMap,
    mapViewModel: TrackerMapViewModel,
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    isAuthenticated: Boolean,
    isServerAccessible: Boolean,
    serverUrl: String,
    onAuthServerUrlChanged: (String) -> Unit,
    onAuthConnect: () -> Unit,
    isConnecting: Boolean,
    onOpenSettings: () -> Unit,
    onHostNavigationRequested: (MapHostNavigationRequest) -> Unit,
    onRequestTrackerParams: (TrackerParamsRouteArgs) -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            GeoVaultTopTitleBar(
                title = stringResource(R.string.map_screen_title),
                actionsContent = {
                    GeoVaultTopBarSettingsMenuAction(
                        onOpenSettings = onOpenSettings,
                        overflowTooltip = stringResource(R.string.tooltip_nav_settings),
                    )
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colors.background),
        ) {
            GeoVaultAuthGate(
                isAuthenticated = isAuthenticated,
                serverUrl = serverUrl,
                onServerUrlChanged = onAuthServerUrlChanged,
                onConnect = onAuthConnect,
                isConnecting = isConnecting,
                connectButtonTooltip = stringResource(R.string.tooltip_settings_connect),
                modifier = Modifier.fillMaxSize(),
            ) {
                TrackerMapAuthenticatedContent(
                    map = map,
                    viewModel = mapViewModel,
                    isActive = isActive,
                    isServerAccessible = isServerAccessible,
                    onHostNavigationRequested = onHostNavigationRequested,
                    onRequestTrackerParams = onRequestTrackerParams,
                )
            }
            TrackerParamsOverlayLayer()
        }
    }
}

@Composable
private fun TrackerMapAuthenticatedContent(
    map: GeoVaultMainMap,
    viewModel: TrackerMapViewModel,
    isActive: Boolean,
    isServerAccessible: Boolean,
    onHostNavigationRequested: (MapHostNavigationRequest) -> Unit,
    onRequestTrackerParams: (TrackerParamsRouteArgs) -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val renderPackage by viewModel.renderPackage.collectAsState()
    val mapPaddingPolicy = remember { TrackerMapPaddingPolicy() }
    val topLeftChipMapper = remember { TrackerMapTopLeftChipMapper() }
    val topLeftChipModel = topLeftChipMapper.map(
        state = state,
        roster = viewModel.trackerRosterForMapChip(),
        acceptedRemoteTrackerIds = viewModel.acceptedRemoteTrackerIdsForCurrentSession(),
    )
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var locationPermission by remember {
        mutableStateOf(TrackingPermissionGate.hasLocationPermission(context))
    }

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

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    locationPermission = TrackingPermissionGate.hasLocationPermission(context)
                    viewModel.onHostResumed()
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

    val activity = context as? Activity
    val application = context.applicationContext as android.app.Application
    val settingsRepo = remember(application) {
        TrackerAppServices.from(application).trackerSettingsRepository()
    }
    val keepScreenOnSetting by settingsRepo.observeSettings()
        .collectAsState(initial = settingsRepo.getSettings())
    val shouldKeepScreenOn = isActive && keepScreenOnSetting.keepScreenOnWhileViewingMap
    DisposableEffect(activity, shouldKeepScreenOn) {
        if (activity != null && shouldKeepScreenOn) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val density = LocalDensity.current
    // MEASURED-NOT-GUESSED CHIP RESERVE: the top-left chip's height varies with its content
    // (name-only vs. name+status vs. name+user-label+status), so a fixed dp guess for how much
    // top viewport to reserve during a bounds fit either wastes space or -- worse -- undershoots
    // the tallest variant and lets a fitted marker land behind the chip. Track the chip's actual
    // rendered height (see the `onGloballyPositioned` on its Box below) and feed that into the
    // bounds-fit padding instead. Falls back to the policy's static guess for the first frame(s)
    // before layout has run, and to zero when no chip is shown at all.
    var topLeftChipMeasuredHeightPx by remember { mutableStateOf(0) }
    val topLeftChipReserveDp = when {
        topLeftChipModel !is TrackerMapTopLeftChipUiModel.Visible -> 0.dp
        topLeftChipMeasuredHeightPx > 0 -> with(density) { topLeftChipMeasuredHeightPx.toDp() }
        else -> TrackerMapPaddingPolicy.FallbackTopLeftChipViewportReserveTopDp
    }
    val boundsFitPaddingPx = remember(density, mapPaddingPolicy, topLeftChipReserveDp) {
        mapPaddingPolicy.computeBoundsFitPaddingPx(density, topLeftChipReserveDp)
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
            displayedTrackerId = state.displayedTrackerId,
            selectedTrackerId = state.runtime.selectedTrackerId,
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
    val userLocationPolicy = remember { TrackerMapUserLocationPolicy() }
    // Viewport scoping is handled entirely by the `LaunchedEffect(viewportContextSeed)` reset
    // below -- `rememberSaveable`'s cross-process persistence would only let this leak across a
    // process-death/restore boundary into a viewport where the puck was never requested, so a
    // plain `remember` is deliberately used instead.
    var liveGpsPuckRequestedThisSession by remember { mutableStateOf(false) }
    val clearMapLocks = remember(viewModel) {
        { viewModel.disableAllMapLocks() }
    }
    var gpsHomeAnchor by remember { mutableStateOf<LatLng?>(null) }
    // LIVE PUCK TRACKING: unlike `gpsHomeAnchor` (a one-shot snapshot captured only when the
    // "my location" FAB resolves a fix), this mirrors every fix the plugin renders while the
    // puck is active, so a live-active-fit re-fit keeps a moving user framed instead of
    // freezing on wherever they happened to be standing at FAB-tap time.
    var liveGpsPuckPosition by remember { mutableStateOf<LatLng?>(null) }
    var didInitialBounds by remember { mutableStateOf(false) }
    // INITIAL-FRAME GATE: covers the map view with a loading overlay until the very
    // first camera directive at this viewport context has been applied (or, as a
    // safety net, until a short timeout has elapsed). The overlay stays until
    // `onMapReady` has run and the camera is positioned. Without this, the user briefly
    // sees the map at MapLibre's default camera (around 0,0) for the time it takes
    // the directive `LaunchedEffect` to schedule + run after `phase` flips to Ready.
    var mapInitialFrameReady by remember { mutableStateOf(false) }
    val viewportContextSeed = remember(
        state.mode,
        state.currentGroupId,
        state.displayedTrackerId,
        state.runtime.selectedTrackerId,
    ) {
        val effectiveDisplayedTrackerId = state.displayedTrackerId
            .ifBlank { state.runtime.selectedTrackerId }
            .trim()
        "${state.mode}|${state.currentGroupId.trim()}|$effectiveDisplayedTrackerId"
    }
    LaunchedEffect(viewportContextSeed) {
        didInitialBounds = false
        gpsHomeAnchor = null
        liveGpsPuckPosition = null
        // Re-arm the loading overlay on every viewport context change so the brief
        // window between "old context's camera position" and "new context's camera
        // fit" is hidden (e.g. switching tracker, switching to group mode).
        mapInitialFrameReady = false
        // "ThisSession" means this viewport context, not the process lifetime -- without
        // resetting here, requesting the GPS puck while viewing one tracker would leak into
        // every other tracker/mode viewed afterwards (e.g. making the live-lock FAB appear for
        // a stream where the puck was never actually requested).
        liveGpsPuckRequestedThisSession = false
        // A taller/shorter chip from the previous viewport must not feed stale padding into the
        // first bounds fit for this one -- fall back to the default reserve until this
        // viewport's own chip (if any) reports its measured height.
        topLeftChipMeasuredHeightPx = 0
    }
    val layerFabAction = remember(map) { geoVaultLayerToggleFabAction(map) }
    val zoomInFabAction = remember(map) { geoVaultZoomInFabAction(map) }
    val zoomOutFabAction = remember(map) { geoVaultZoomOutFabAction(map) }
    val gpsFabAction = key(viewportContextSeed) {
        rememberGeoVaultGpsOneShotMyLocationFabAction(
            map = map,
            userLocation = locationPlugin,
            order = 30,
            onLocationResolved = { latLng ->
                gpsHomeAnchor = latLng
                locationPermission = TrackingPermissionGate.hasLocationPermission(context)
            },
            coordinateOverride = {
                // RUNTIME-TRACKING RECENTER: while actively recording, the user's tracker marker
                // already represents their position. The default FAB path enables the MapLibre
                // user-location plugin and renders a synthetic puck — that paints a duplicate
                // chevron on top of the tracker marker (TrackerMapUserLocationPolicy intentionally
                // suppresses the plugin while tracking). Provide the runtime tracker coord so the
                // controller animates the camera to it without enabling the puck.
                val runtime = state.runtime
                if (runtime.localRecordingActive) {
                    val lat = runtime.lastTrackedLatitude
                    val lon = runtime.lastTrackedLongitude
                    if (lat != null && lon != null) LatLng(lat, lon) else null
                } else {
                    locationPlugin.getLastLocation()
                        ?.let { latLngOrNull(it.latitude, it.longitude) }
                }
            },
        )
    }

    DisposableEffect(locationPlugin) {
        val listener: (Location) -> Unit = { location ->
            liveGpsPuckPosition = latLngOrNull(location.latitude, location.longitude)
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

    val userLocationDecision = remember(
        isActive,
        locationPermission,
        phase,
        liveGpsPuckRequestedThisSession,
        state.runtime.gpsCollecting
    ) {
        userLocationPolicy.evaluate(
            TrackerMapUserLocationInput(
                isMapActive = isActive,
                hasLocationPermission = locationPermission,
                isMapReady = phase == GeoVaultMapPhase.Ready,
                userLocationRequestedThisSession = liveGpsPuckRequestedThisSession,
                runtimeRunning = state.runtime.gpsCollecting
            )
        )
    }

    // ORPHAN GUARD: both anchors are captured/updated only while the puck is enabled -- once it
    // is disabled (permission revoked, backgrounded, runtime tracking took over, etc.) they must
    // not survive to be unioned into a later fit as stale, no-longer-current positions.
    LaunchedEffect(userLocationDecision.shouldEnablePuck) {
        if (!userLocationDecision.shouldEnablePuck) {
            gpsHomeAnchor = null
            liveGpsPuckPosition = null
        }
    }

    DisposableEffect(map, userLocationDecision.shouldStreamGps) {
        if (userLocationDecision.shouldStreamGps) {
            locationPlugin.startRenderingGpsLocation(intervalMs = 2000L)
        }
        onDispose {
            locationPlugin.stopRenderingGpsLocation()
        }
    }

    LaunchedEffect(phase, userLocationDecision, viewportContextSeed) {
        if (phase != GeoVaultMapPhase.Ready) return@LaunchedEffect
        locationPlugin.setEnabled(userLocationDecision.shouldEnablePuck)
        locationPlugin.setCameraTracking(false)
    }

    DisposableEffect(map) {
        val listener = geoVaultCreateGestureMoveStartedListener {
            viewModel.disableAllMapLocks()
        }
        map.addOnCameraMoveStartedListener(listener)
        onDispose {
            map.removeOnCameraMoveStartedListener(listener)
        }
    }
    LaunchedEffect(
        phase,
        renderPackage.revision,
    ) {
        if (phase != GeoVaultMapPhase.Ready) return@LaunchedEffect
        delay(RENDER_COALESCE_MS)
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
                    // GPS ANCHOR UNION: a one-shot explicit fit (the "Home" FAB) additionally
                    // frames in the last-resolved GPS one-shot anchor, if any, so the position
                    // the user tapped "my location" at stays in view for that single fit. An
                    // ongoing live-active-fit re-fit instead unions the *live*, continuously-
                    // updating puck position -- using the one-shot anchor there would freeze the
                    // union at wherever the user happened to be standing when the FAB was
                    // originally tapped, silently falling out of frame as they walk away from it.
                    // Both anchors are Compose-local UI state the ViewModel has no notion of, so
                    // the union happens here rather than at request time.
                    val effectiveBounds = when (directive.reason) {
                        com.geovault.tracker.presentation.TrackerMapCameraDirective.Reason.ExplicitFit -> {
                            gpsHomeAnchor?.let { anchor -> geoVaultLatLngBoundsUnion(directive.bounds, listOf(anchor)) }
                                ?: directive.bounds
                        }
                        com.geovault.tracker.presentation.TrackerMapCameraDirective.Reason.LiveActiveFit -> {
                            liveGpsPuckPosition
                                .takeIf { userLocationDecision.shouldEnablePuck }
                                ?.let { anchor -> geoVaultLatLngBoundsUnion(directive.bounds, listOf(anchor)) }
                                ?: directive.bounds
                        }
                        else -> directive.bounds
                    }
                    fitTrackerMapBounds(
                        map = map,
                        bounds = effectiveBounds,
                        boundsFitPaddingPx = boundsFitPaddingPx,
                        mode = directive.mode,
                    )
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
            visible = state.batteryOptimizationHintVisible,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            // Keep persistent viewport padding at zero. MapLibre Native's
            // `TransformState::constrain` clamps the camera centre to the Web-Mercator
            // ±85° band using the full viewport height but does NOT compensate for
            // `edgeInsets`, so any non-zero top or bottom viewport padding lets the user
            // pan the camera past the world edge and exposes the MapView underlay.
            // Top/left reserves for the chip and FAB column are still applied to
            // bounds-fit camera updates via `boundsFitPaddingPx`, which is one-shot and
            // therefore doesn't leave a persistent camera offset.
            GeoVaultMainMapView(
                modifier = Modifier.fillMaxSize(),
                map = map,
                showDefaultSourceToggle = false,
                includeDefaultFabColumnPadding = false,
                mapPaddingDp = GeoVaultMapPaddingDp(),
                suppressMapLoadErrorDialog = !isServerAccessible,
            )

            val effectiveDisplayedTrackerId = state.displayedTrackerId
                .ifBlank { state.runtime.selectedTrackerId }
                .trim()
            val lockFabBehavior = TrackerMapLockFabPolicy.resolve(
                TrackerMapLockFabInput(
                    mode = state.mode,
                    displayedTrackerId = effectiveDisplayedTrackerId,
                    selectionLockTrackerId = state.selectionLockTrackerId,
                    liveActiveFitEnabled = state.liveActiveFitEnabled,
                    followLockEnabled = state.followLockEnabled,
                )
            )
            val selectionLockBehavior = lockFabBehavior as? TrackerMapLockFabBehavior.SelectionLock
            val singleTrackerLocked = selectionLockBehavior?.isLocked == true
            val isSelectedDefaultTracker = selectionLockBehavior != null &&
                selectionLockBehavior.displayedTrackerId == state.runtime.selectedTrackerId.trim()
            // MULTI-TRACKER GATE: fitting bounds around a single point is indistinguishable from
            // centering on it, so the live-active-fit toggle only earns its keep once there's a
            // second tracker/position sharing the map. This is deliberately just the user's own
            // GPS puck -- a locally-recorded tracker different from the one currently displayed
            // was considered here too, but SINGLE_SESSION bounds (trailBoundsOrNull in
            // MapTrailDisplaySubsystem) and point routing (TrackerMapPointRouter.routeLocal) both
            // only ever use the *displayed* tracker's own trail/position; a differing overlay
            // tracker's points are accepted but never appended to any trail or unioned into
            // bounds in this mode. Gating on it here would show a toggle that's a pure no-op.
            // Hoisted above the FAB builder (rather than computed inline) so the auto-clear
            // effect below can react to it too.
            val hasMultipleTrackersOnMap = userLocationDecision.shouldEnablePuck
            // STUCK-LIVE-FIT GUARD: once this gate drops to false, the secondary FAB that would
            // let the user turn live active fit back off disappears too (see
            // TrackerMapLiveActiveFitPolicy.resolveVisibility) -- without this, a toggle enabled
            // while a second tracker/GPS puck was present would stay silently stuck on forever
            // once that second position source goes away (e.g. GPS puck disabled, or the local
            // recording overlay tracker changes). Auto-clearing here preserves the selection lock
            // (see MapContextSubsystem.setLiveActiveFit) -- only the live-fit modifier itself is
            // dropped.
            LaunchedEffect(hasMultipleTrackersOnMap, viewportContextSeed) {
                if (!hasMultipleTrackersOnMap && state.liveActiveFitEnabled) {
                    viewModel.setLiveActiveFit(false)
                }
            }
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
                if (
                    TrackerMapMyLocationFabPolicy.shouldShowFab(
                        mode = state.mode,
                        displayedTrackerId = state.displayedTrackerId,
                        selectedTrackerId = state.runtime.selectedTrackerId,
                    )
                ) {
                    action(
                        id = gpsFabAction.id,
                        order = gpsFabAction.order,
                        icon = gpsFabAction.icon,
                        contentDescription = fabDescLiveGpsPuck,
                        tooltip = tooltipMapLiveGpsPuck,
                        onTap = {
                            liveGpsPuckRequestedThisSession = true
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
                val liveActiveFitLockArmed = TrackerMapLiveActiveFitPolicy.resolveLockArmed(
                    singleTrackerLocked = singleTrackerLocked,
                )
                val liveActiveFitVisibility = TrackerMapLiveActiveFitPolicy.resolveVisibility(
                    LiveActiveFitInput(
                        mode = state.mode,
                        followLockArmed = liveActiveFitLockArmed,
                        liveActiveFitEnabled = state.liveActiveFitEnabled,
                        hasTrailPoints = state.trail.isNotEmpty(),
                        isSelectedDefaultTracker = isSelectedDefaultTracker,
                        hasMultipleTrackersOnMap = hasMultipleTrackersOnMap,
                    )
                )
                if (liveActiveFitVisibility.showButton) {
                    action(
                        id = "live_active_fit",
                        order = 32,
                        icon = GeoVaultMapFabIcon.Drawable(
                            if (state.liveActiveFitEnabled) R.drawable.ic_live_active_fit_on
                            else R.drawable.ic_live_active_fit_off
                        ),
                        contentDescription = if (state.liveActiveFitEnabled) {
                            fabDescLiveActiveFitDisable
                        } else {
                            fabDescLiveActiveFitEnable
                        },
                        enabled = liveActiveFitVisibility.buttonEnabled,
                        tooltip = tooltipMapLiveActiveFit,
                        onTap = {
                            viewModel.setLiveActiveFit(!state.liveActiveFitEnabled)
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
                            // GESTURE PARITY: a manual zoom is exactly as much a user takeover of
                            // the camera as a pan is -- without this, zooming in/out with a lock
                            // engaged would fight the lock's next re-fit instead of releasing it.
                            viewModel.disableAllMapLocks()
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
                            viewModel.disableAllMapLocks()
                            zoomOutFabAction.onTap?.invoke()
                        }
                    },
                )
            }

            GeoVaultMapFabColumn(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 16.dp, end = 16.dp),
                actions = mapFabActions,
            )

            if (topLeftChipModel is TrackerMapTopLeftChipUiModel.Visible) {
                BoxWithConstraints(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        // Placed before `padding` so the measured size includes the top offset
                        // below, i.e. the full reserved region from the top of the map down to
                        // below the chip -- exactly what `boundsFitPaddingPx` above needs.
                        .onGloballyPositioned { coordinates ->
                            topLeftChipMeasuredHeightPx = coordinates.size.height
                        }
                        .padding(top = 16.dp, start = 16.dp, end = 80.dp),
                ) {
                    // KEYED ON VIEWPORT: forces the chip's internal `remember`ed interaction
                    // state (tooltip-suppression, tracked card bounds) to reset when the tracker
                    // being viewed changes, instead of silently carrying over state that was
                    // computed for a different tracker's chip.
                    key(viewportContextSeed) {
                        MapTopLeftTrackerChip(
                            modifier = Modifier.widthIn(max = maxWidth),
                            model = topLeftChipModel,
                            onCardClick = {
                                onHostNavigationRequested(
                                    MapHostNavigationRequestResolver.fromListNavigationTarget(
                                        viewModel.resolveListNavigationTarget()
                                    )
                                )
                            },
                            onResetClick = viewModel::restoreSelectedTrackerMapContext,
                        )
                    }
                }
            }
            if (state.isGeometryLoading) {
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
            val gpsAccuracyIndicatorModel = TrackerMapGpsAccuracyIndicatorPolicy.resolve(state.runtime)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (gpsAccuracyIndicatorModel.isVisible) {
                    MapGpsAccuracyIndicator()
                }
                MapStreamingIndicator(
                    model = state.streamingStatus,
                )
            }
            val selectionModel = state.toSelectionPanelUiModel()
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(),
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
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(geoVaultHairlineDividerColor()),
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = androidx.compose.ui.graphics.RectangleShape,
                        backgroundColor = MaterialTheme.colors.background,
                        elevation = 0.dp,
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            MapTrackerSelectionPanel(
                                model = selectionModel,
                                onViewInList = {
                                    onHostNavigationRequested(
                                        MapHostNavigationRequestResolver.fromListNavigationTarget(
                                            viewModel.resolveListNavigationTarget(selectionModel.trackerId)
                                        )
                                    )
                                },
                                onViewParams = {
                                    onRequestTrackerParams(selectionModel.toTrackerParamsRouteArgs())
                                },
                                onFocus = viewModel::focusSelectedTrackerOnMap,
                                onToggleLock = viewModel::toggleSelectedTrackerLock,
                                onClear = viewModel::clearMapTrackerSelection,
                            )
                        }
                    }
                }
            }
            // INITIAL-FRAME LOADING SHIELD: drawn last so it occludes everything in
            // the map area (map view, FABs, chips, indicators) until the very first
            // camera directive for the current viewport context has been applied.
            // The shield stays until the first position is set after map ready; otherwise
            // the user briefly sees MapLibre's default camera (~0,0) before the
            // LaunchedEffect that consumes the directive can run on the same frame
            // `phase` flips Ready.
            // Touch is swallowed so the user can't pan the still-loading map.
            if (!mapInitialFrameReady) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colors.background)
                        .pointerInteropFilter { true },
                    contentAlignment = Alignment.Center,
                ) {
                    GeoVaultLoadingSpinner(
                        bottomText = stringResource(R.string.map_status_map_loading),
                    )
                }
            }
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

private fun TrackerMapUiState.toSelectionPanelUiModel(): MapSelectionPanelUiModel? {
    val selection = selectedMapTracker ?: return null
    if (!isBottomCardVisible) return null
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
        isLocked = selectionLockTrackerId == selection.trackerId,
        showFocusAction = TrackerMapViewModel.resolveFocusActionVisible(mode),
    )
}

private fun MapSelectionPanelUiModel.toTrackerParamsRouteArgs(): TrackerParamsRouteArgs {
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
    ).toTrackerParamsRouteArgs()
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
            val latLon = String.format(Locale.US, "%.4f, %.4f", model.latitude, model.longitude)
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

