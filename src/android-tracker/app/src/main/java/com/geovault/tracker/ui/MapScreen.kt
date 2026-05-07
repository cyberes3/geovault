package com.geovault.tracker.ui

import android.app.Activity
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInteropFilter
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
import com.geovault.common.maps.core.GeoVaultMainMap
import com.geovault.common.maps.core.GeoVaultMainMapView
import com.geovault.common.maps.core.GeoVaultMapPaddingDp
import com.geovault.common.maps.core.GeoVaultMapPhase
import com.geovault.common.maps.core.animateCameraToFitLatLngBounds
import com.geovault.common.maps.core.geoVaultCenterCameraPreserveZoom
import com.geovault.common.maps.core.geoVaultCreateGestureMoveStartedListener
import com.geovault.common.maps.core.geoVaultLatLngBoundsUnion
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
import com.geovault.tracker.presentation.TrackerMapGpsAccuracyIndicatorPolicy
import com.geovault.tracker.presentation.TrackerMapLiveActiveFitPolicy
import com.geovault.tracker.presentation.TrackerMapRenderContract
import com.geovault.tracker.presentation.TrackerMapSelectionCard
import com.geovault.tracker.presentation.TrackerMapTopLeftChipMapper
import com.geovault.tracker.presentation.TrackerMapTopLeftChipUiModel
import com.geovault.tracker.presentation.TrackerMapUiState
import com.geovault.tracker.presentation.TrackerMapMyLocationFabPolicy
import com.geovault.tracker.presentation.TrackerMapUserLocationInput
import com.geovault.tracker.presentation.TrackerMapUserLocationPolicy
import com.geovault.tracker.presentation.TrackerMapViewModel
import org.maplibre.android.geometry.LatLng
import java.util.Locale

private const val RENDER_COALESCE_MS = 120L

@Composable
fun MapScreen(
    map: GeoVaultMainMap,
    mapViewModel: TrackerMapViewModel,
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    isAuthenticated: Boolean,
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
    onHostNavigationRequested: (MapHostNavigationRequest) -> Unit,
    onRequestTrackerParams: (TrackerParamsRouteArgs) -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val renderPackage by viewModel.renderPackage.collectAsState()
    val latestRenderPackage by rememberUpdatedState(renderPackage)
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

    DisposableEffect(viewModel, isActive) {
        if (isActive) {
            viewModel.onMapSurfaceVisible()
        } else {
            viewModel.onMapSurfaceHidden()
        }
        onDispose {
            if (isActive) {
                viewModel.onMapSurfaceHidden()
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
    val boundsFitPaddingPx = remember(density, mapPaddingPolicy) {
        mapPaddingPolicy.computeBoundsFitPaddingPx(density)
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
    var followLockArmedThisSession by rememberSaveable { mutableStateOf(false) }
    val disarmFollowSessionAndClearMapLocks = remember(viewModel) {
        {
            followLockArmedThisSession = false
            viewModel.disableAllMapLocks()
        }
    }
    var gpsHomeAnchor by remember { mutableStateOf<LatLng?>(null) }
    var didInitialBounds by remember { mutableStateOf(false) }
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
    }
    val layerFabAction = remember(map) { geoVaultLayerToggleFabAction(map) }
    val zoomInFabAction = remember(map) { geoVaultZoomInFabAction(map) }
    val zoomOutFabAction = remember(map) { geoVaultZoomOutFabAction(map) }
    val gpsFabAction = key(viewportContextSeed) {
        rememberGeoVaultGpsOneShotMyLocationFabAction(
            map = map,
            userLocation = locationPlugin,
            order = 30,
            onLocationResolved = { latLng -> gpsHomeAnchor = latLng },
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
                    null
                }
            },
        )
    }

    DisposableEffect(map) {
        map.registerPlugin(renderPlugin)
        map.registerPlugin(markerIconPlugin)
        map.registerPlugin(locationPlugin)
        onDispose {
            renderPlugin.onRenderedMapHitSelected = null
            renderPlugin.onRenderedMapBackgroundTapped = null
            map.unregisterPlugin(renderPlugin)
            map.unregisterPlugin(markerIconPlugin)
            map.unregisterPlugin(locationPlugin)
        }
    }

    val fabDescSource = stringResource(R.string.map_fab_toggle_source)
    val fabDescFitTrail = stringResource(R.string.map_fab_fit_trail)
    val fabDescFollow = stringResource(R.string.map_fab_follow_lock)
    val fabDescZoomIn = stringResource(R.string.map_fab_zoom_in)
    val fabDescZoomOut = stringResource(R.string.map_fab_zoom_out)
    val fabDescLockSelection = stringResource(R.string.map_action_lock_selection)
    val fabDescUnlockSelection = stringResource(R.string.map_action_unlock_selection)
    val fabDescLiveActiveFitEnable = stringResource(R.string.live_active_fit_enable)
    val fabDescLiveActiveFitDisable = stringResource(R.string.live_active_fit_disable)
    val tooltipMapLayers = stringResource(R.string.tooltip_map_layers)
    val tooltipMapZoomLatest = stringResource(R.string.tooltip_map_zoom_latest)
    val tooltipMapZoomIn = stringResource(R.string.tooltip_map_zoom_in)
    val tooltipMapZoomOut = stringResource(R.string.tooltip_map_zoom_out)
    val tooltipMapLiveActiveFit = stringResource(R.string.tooltip_map_live_active_fit)
    val tooltipMapSelectionZoomLock = stringResource(R.string.tooltip_map_selection_zoom_lock)

    val phase by map.phase.collectAsState()
    LaunchedEffect(phase, isActive) {
        viewModel.setMapReady(isActive && phase == GeoVaultMapPhase.Ready)
    }
    LaunchedEffect(isActive, state.followLockEnabled) {
        if (!isActive || !state.followLockEnabled) {
            followLockArmedThisSession = false
        }
    }

    val userLocationDecision = remember(
        isActive,
        locationPermission,
        phase,
        followLockArmedThisSession,
        state.followLockEnabled,
        state.runtime.gpsCollecting
    ) {
        userLocationPolicy.evaluate(
            TrackerMapUserLocationInput(
                isMapActive = isActive,
                hasLocationPermission = locationPermission,
                isMapReady = phase == GeoVaultMapPhase.Ready,
                userFollowLockArmedThisSession = followLockArmedThisSession,
                followLockEnabled = state.followLockEnabled,
                runtimeRunning = state.runtime.gpsCollecting
            )
        )
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
        locationPlugin.setCameraTracking(userLocationDecision.shouldEnableFollowCamera)
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
        val resolvedState = markerIconPlugin.resolveRenderStateWithFallback(renderPackage.renderState)
        renderPlugin.setRenderState(resolvedState)
    }

    // CAMERA-DIRECTIVE: single consumer for VM-resolved camera moves. Precedence is enforced
    // upstream so this effect doesn't have to consider whether follow-lock or selection-lock or
    // initial-fit "wins"; it just applies whatever the VM resolved. Tracks consumed directive
    // ids for InitialFit so the one-shot semantics survive bounds shape churn (e.g. trail growth)
    // until the viewport context resets and re-arms them.
    val cameraDirective by viewModel.cameraDirective.collectAsState()
    LaunchedEffect(phase, cameraDirective.id) {
        if (phase != GeoVaultMapPhase.Ready) return@LaunchedEffect
        when (val directive = cameraDirective) {
            is com.geovault.tracker.presentation.TrackerMapCameraDirective.None -> Unit
            is com.geovault.tracker.presentation.TrackerMapCameraDirective.CenterPreserveZoom -> {
                geoVaultCenterCameraPreserveZoom(map, directive.latitude, directive.longitude)
            }
            is com.geovault.tracker.presentation.TrackerMapCameraDirective.FitBounds -> {
                if (directive.reason == com.geovault.tracker.presentation.TrackerMapCameraDirective.Reason.InitialFit) {
                    if (didInitialBounds) return@LaunchedEffect
                    map.moveCameraToFitLatLngBounds(directive.bounds, boundsFitPaddingPx)
                    didInitialBounds = true
                } else {
                    map.moveCameraToFitLatLngBounds(directive.bounds, boundsFitPaddingPx)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.fitTrailEvents.collect {
            if (map.phase.value != GeoVaultMapPhase.Ready) return@collect
            // FIT-FRESHNESS: compute bounds at the moment of fit instead of reading the cached
            // render-package bounds. The render package is published asynchronously off
            // _uiState.collect, so when a fit is requested in the same tick that flipped
            // _uiState.value (e.g. immediately after a server reload completes) the cached
            // bounds can still reflect the previous frame and the camera animates to stale data.
            val bounds = viewModel.trailBoundsOrNull() ?: latestRenderPackage.bounds
            val anchor = gpsHomeAnchor
            val effective = when {
                bounds != null && anchor != null -> geoVaultLatLngBoundsUnion(bounds, listOf(anchor))
                else -> bounds
            }
            if (effective != null) {
                map.animateCameraToFitLatLngBounds(effective, boundsFitPaddingPx)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background),
    ) {
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
            )

            val effectiveDisplayedTrackerId = state.displayedTrackerId
                .ifBlank { state.runtime.selectedTrackerId }
                .trim()
            val singleTrackerMapView = state.mode == TrackerMapDisplayMode.SINGLE_SESSION &&
                effectiveDisplayedTrackerId.isNotEmpty()
            val groupMapView = state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER
            val lockSelected = if (singleTrackerMapView) {
                state.selectionLockTrackerId == effectiveDisplayedTrackerId
            } else if (groupMapView) {
                state.liveActiveFitEnabled
            } else {
                state.followLockEnabled
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
                        disarmFollowSessionAndClearMapLocks()
                        if (phase == GeoVaultMapPhase.Ready) {
                            geoVaultResetCameraBearingAndTilt(map)
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
                        contentDescription = gpsFabAction.contentDescription,
                        onTap = {
                            viewModel.disableAllMapLocks()
                            gpsFabAction.onTap?.invoke()
                        },
                    )
                }
                action(
                    id = "follow_lock",
                    order = 25,
                    icon = GeoVaultMapFabIcon.Vector(
                        if (lockSelected) Icons.Default.Lock else Icons.Outlined.LockOpen,
                    ),
                    contentDescription = if (singleTrackerMapView) {
                        if (lockSelected) {
                            fabDescUnlockSelection
                        } else {
                            fabDescLockSelection
                        }
                    } else if (groupMapView) {
                        if (state.liveActiveFitEnabled) {
                            fabDescLiveActiveFitDisable
                        } else {
                            fabDescLiveActiveFitEnable
                        }
                    } else {
                        fabDescFollow
                    },
                    tooltip = if (groupMapView) tooltipMapLiveActiveFit else tooltipMapSelectionZoomLock,
                    onTap = {
                        if (singleTrackerMapView) {
                            viewModel.toggleDisplayedTrackerLock()
                        } else if (groupMapView) {
                            followLockArmedThisSession = false
                            viewModel.setLiveActiveFit(!state.liveActiveFitEnabled)
                        } else {
                            val nextEnabled = !lockSelected
                            followLockArmedThisSession = nextEnabled
                            viewModel.setFollowLock(nextEnabled)
                        }
                    },
                )
                val isSelectedDefaultTracker = singleTrackerMapView &&
                    effectiveDisplayedTrackerId == state.runtime.selectedTrackerId.trim()
                val liveActiveFitLockArmed = TrackerMapLiveActiveFitPolicy.resolveLockArmed(
                    singleTrackerMapView = singleTrackerMapView,
                    singleTrackerLocked = lockSelected,
                    multiFollowLockArmed = followLockArmedThisSession,
                )
                val liveActiveFitVisibility = TrackerMapLiveActiveFitPolicy.resolveVisibility(
                    LiveActiveFitInput(
                        mode = state.mode,
                        runtimeRunning = state.runtime.localRecordingActive,
                        followLockArmed = liveActiveFitLockArmed,
                        liveActiveFitEnabled = state.liveActiveFitEnabled,
                        hasTrailPoints = state.trail.isNotEmpty(),
                        isSelectedDefaultTracker = isSelectedDefaultTracker,
                    )
                )
                if (liveActiveFitVisibility.showButton && !groupMapView) {
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
                        .padding(top = 16.dp, start = 16.dp, end = 80.dp),
                ) {
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
    val accuracyMeters: Float?,
    val isLocked: Boolean,
    val showFocusAction: Boolean,
)

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
            var staleEvalTick by remember(model.trackerId) { mutableStateOf(0) }
            LaunchedEffect(model.trackerId) {
                while (true) {
                    delay(20_000L)
                    staleEvalTick++
                }
            }
            val nowMs = System.currentTimeMillis() + (staleEvalTick and 0)
            val lastUpdatedText = MapFormatLastUpdatedTextOrWaiting(model.lastUpdatedMs)
            val warnStale = model.lastUpdatedMs != null &&
                ActiveButDeadTrackerPolicy.isActiveButDead(
                    nowMs = nowMs,
                    updatedAtMs = model.serverMetadataUpdatedAtMs,
                    lastDataMs = model.lastUpdatedMs,
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

