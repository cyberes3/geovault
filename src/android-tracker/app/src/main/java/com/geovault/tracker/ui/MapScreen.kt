package com.geovault.tracker.ui

import android.app.Activity
import android.graphics.PointF
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.geovault.common.maps.ui.GeoVaultMapFabColumn
import com.geovault.common.maps.ui.GeoVaultMapFabIcon
import com.geovault.common.maps.ui.buildGeoVaultMapFabActions
import com.geovault.common.maps.ui.geoVaultLayerToggleFabAction
import com.geovault.common.maps.ui.geoVaultZoomInFabAction
import com.geovault.common.maps.ui.geoVaultZoomOutFabAction
import com.geovault.common.maps.ui.rememberGeoVaultGpsRecenterFabAction
import com.geovault.common.ClipboardCopyHelper
import com.geovault.common.ui.components.GeoVaultAuthGate
import com.geovault.common.ui.components.GeoVaultClickableWithTooltip
import com.geovault.common.ui.components.GeoVaultIconButton
import com.geovault.common.ui.components.GeoVaultLoadingSpinner
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.components.GeoVaultTopBarSettingsMenuAction
import com.geovault.common.ui.components.GeoVaultTopTitleBar
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.tracker.di.TrackerAppServices
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
import com.geovault.tracker.presentation.TrackerMapUserLocationInput
import com.geovault.tracker.presentation.TrackerMapUserLocationPolicy
import com.geovault.tracker.presentation.TrackerMapViewModel
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
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
                        isAuthenticated = isAuthenticated,
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
                .background(GeoVaultColorTokens.Background),
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
    val mapPaddingPolicy = remember { TrackerMapPaddingPolicy() }
    val topLeftChipMapper = remember { TrackerMapTopLeftChipMapper() }
    val topLeftChipModel = topLeftChipMapper.map(state)
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
                disablePointSymbolFade = true,
                defaultIconSize = 0.75f,
                showPolygonOutline = false,
                defaultPolygonFillOpacity = 1f,
            ),
            context = context,
        )
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
    val layerFabAction = remember(map) { geoVaultLayerToggleFabAction(map) }
    val zoomInFabAction = remember(map) { geoVaultZoomInFabAction(map) }
    val zoomOutFabAction = remember(map) { geoVaultZoomOutFabAction(map) }
    val gpsFabAction = rememberGeoVaultGpsRecenterFabAction(
        map = map,
        userLocation = locationPlugin,
        order = 30,
        onLocationResolved = { latLng -> gpsHomeAnchor = latLng },
    )

    DisposableEffect(map) {
        map.registerPlugin(renderPlugin)
        map.registerPlugin(markerIconPlugin)
        map.registerPlugin(locationPlugin)
        onDispose {
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
    LaunchedEffect(phase) {
        viewModel.setMapReady(phase == GeoVaultMapPhase.Ready)
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
        state.runtime.isRunning
    ) {
        userLocationPolicy.evaluate(
            TrackerMapUserLocationInput(
                isMapActive = isActive,
                hasLocationPermission = locationPermission,
                isMapReady = phase == GeoVaultMapPhase.Ready,
                userFollowLockArmedThisSession = followLockArmedThisSession,
                followLockEnabled = state.followLockEnabled,
                runtimeRunning = state.runtime.isRunning
            )
        )
    }

    DisposableEffect(map, userLocationDecision.shouldStreamGps) {
        if (userLocationDecision.shouldStreamGps) {
            locationPlugin.startRenderingGpsLocation(intervalMs = 2000L)
        }
        onDispose {
            if (userLocationDecision.shouldStreamGps) {
                locationPlugin.stopRenderingGpsLocation()
            }
        }
    }

    LaunchedEffect(phase, userLocationDecision) {
        if (phase != GeoVaultMapPhase.Ready) return@LaunchedEffect
        locationPlugin.setEnabled(userLocationDecision.shouldEnablePuck)
        locationPlugin.setCameraTracking(userLocationDecision.shouldEnableFollowCamera)
    }

    LaunchedEffect(
        phase,
        state.followLockEnabled,
        state.runtime.isRunning,
        state.runtime.lastTrackedLatitude,
        state.runtime.lastTrackedLongitude,
        state.trail
    ) {
        if (phase != GeoVaultMapPhase.Ready) return@LaunchedEffect
        if (!state.followLockEnabled || !state.runtime.isRunning) return@LaunchedEffect
        val targetLat = state.runtime.lastTrackedLatitude ?: state.trail.lastOrNull()?.latitude ?: return@LaunchedEffect
        val targetLon = state.runtime.lastTrackedLongitude ?: state.trail.lastOrNull()?.longitude ?: return@LaunchedEffect
        geoVaultCenterCameraPreserveZoom(map, targetLat, targetLon)
    }
    LaunchedEffect(
        phase,
        state.selectionLockTrackerId,
        state.remoteLastPoints,
        state.trail,
        state.displayedTrackerId,
        state.runtime.selectedTrackerId,
    ) {
        if (phase != GeoVaultMapPhase.Ready) return@LaunchedEffect
        val lockPoint = viewModel.selectionLockPointOrNull() ?: return@LaunchedEffect
        geoVaultCenterCameraPreserveZoom(map, lockPoint.first, lockPoint.second)
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
    DisposableEffect(
        map,
        state.displayedTrackerId,
        state.runtime.selectedTrackerId,
    ) {
        val clickListener = MapLibreMap.OnMapClickListener { latLng ->
            val maplibreMap = map.maplibreMap ?: return@OnMapClickListener false
            val screenPoint: PointF = maplibreMap.projection.toScreenLocation(latLng)
            val features = runCatching {
                maplibreMap.queryRenderedFeatures(
                    screenPoint,
                    TrackerMapRenderContract.pointsLabelLayerId()
                )
            }.getOrElse { emptyList() }
            val nearest = selectNearestFeature(maplibreMap, screenPoint, features)
            val trackId = nearest?.let { feature ->
                val id = feature.getStringProperty("id") ?: return@let null
                when {
                    id == "last-fix" -> {
                        state.displayedTrackerId.ifBlank { state.runtime.selectedTrackerId }
                    }
                    id.startsWith("remote-") -> id.removePrefix("remote-")
                    else -> null
                }?.trim()?.takeIf { it.isNotEmpty() }
            }
            if (trackId != null) {
                viewModel.onTrackerMarkerTapped(trackId)
                true
            } else {
                viewModel.onMapBackgroundTapped()
            }
        }
        map.addOnMapClickListener(clickListener)
        onDispose { map.removeOnMapClickListener(clickListener) }
    }

    LaunchedEffect(
        phase,
        state.trail,
        state.allQueueTrailsByTracker,
        state.runtime,
        state.mode,
        state.remoteLastPoints,
        state.activeStreamedTrackerIds,
        state.selectedMapTracker,
    ) {
        if (phase != GeoVaultMapPhase.Ready) return@LaunchedEffect
        delay(RENDER_COALESCE_MS)
        val renderState = viewModel.buildMapRenderState()
        val resolvedState = markerIconPlugin.resolveRenderStateWithFallback(renderState)
        renderPlugin.setRenderState(resolvedState)
    }

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
    }
    LaunchedEffect(phase, state.mode, state.currentGroupId, state.trail, state.allQueueTrailsByTracker, state.runtime) {
        if (phase != GeoVaultMapPhase.Ready) return@LaunchedEffect
        if (didInitialBounds) return@LaunchedEffect
        val bounds = viewModel.trailBoundsOrNull()
        if (bounds != null) {
            map.moveCameraToFitLatLngBounds(bounds, boundsFitPaddingPx)
            didInitialBounds = true
        }
    }

    LaunchedEffect(Unit) {
        viewModel.fitTrailEvents.collect {
            if (map.phase.value != GeoVaultMapPhase.Ready) return@collect
            val bounds = viewModel.trailBoundsOrNull()
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
            .background(GeoVaultColorTokens.Background),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            GeoVaultMainMapView(
                modifier = Modifier.fillMaxSize(),
                map = map,
                showDefaultSourceToggle = false,
                includeDefaultFabColumnPadding = mapPaddingPolicy.includeDefaultFabColumnPadding,
                mapPaddingDp = mapPaddingPolicy.mapPaddingDp,
            )

            val effectiveDisplayedTrackerId = state.displayedTrackerId
                .ifBlank { state.runtime.selectedTrackerId }
                .trim()
            val singleTrackerMapView = state.mode == TrackerMapDisplayMode.SINGLE_SESSION &&
                effectiveDisplayedTrackerId.isNotEmpty()
            val lockSelected = if (singleTrackerMapView) {
                state.selectionLockTrackerId == effectiveDisplayedTrackerId
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
                if (!singleTrackerMapView) {
                    action(
                        id = gpsFabAction.id,
                        order = gpsFabAction.order,
                        icon = gpsFabAction.icon,
                        contentDescription = gpsFabAction.contentDescription,
                        onTap = {
                            disarmFollowSessionAndClearMapLocks()
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
                    } else {
                        fabDescFollow
                    },
                    tooltip = tooltipMapSelectionZoomLock,
                    onTap = {
                        if (singleTrackerMapView) {
                            viewModel.toggleDisplayedTrackerLock()
                        } else {
                            val nextEnabled = !lockSelected
                            followLockArmedThisSession = nextEnabled
                            viewModel.setFollowLock(nextEnabled)
                        }
                    },
                )
                val isSelectedDefaultTracker = singleTrackerMapView &&
                    effectiveDisplayedTrackerId == state.runtime.selectedTrackerId.trim()
                val liveActiveFitVisibility = TrackerMapLiveActiveFitPolicy.resolveVisibility(
                    LiveActiveFitInput(
                        mode = state.mode,
                        runtimeRunning = state.runtime.isRunning,
                        followLockArmed = followLockArmedThisSession,
                        liveActiveFitEnabled = state.liveActiveFitEnabled,
                        hasTrailPoints = state.trail.isNotEmpty(),
                        isSelectedDefaultTracker = isSelectedDefaultTracker,
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
                MapTopLeftTrackerChip(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 16.dp, start = 16.dp),
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
            if (selectionModel != null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(),
                ) {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(GeoVaultColorTokens.BorderLight),
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = androidx.compose.ui.graphics.RectangleShape,
                        backgroundColor = GeoVaultColorTokens.Background,
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
        color = GeoVaultColorTokens.Background,
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
                    color = GeoVaultColorTokens.TextPrimary,
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
                        tint = GeoVaultColorTokens.PrimaryBlue,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(modifier = Modifier.size(8.dp))
                GeoVaultClickableWithTooltip(
                    onClick = onClear,
                    modifier = Modifier
                        .size(28.dp)
                        .background(
                            color = GeoVaultColorTokens.BorderLight,
                            shape = CircleShape,
                        ),
                    tooltip = stringResource(R.string.tooltip_map_selection_close),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.trackers_dialog_cancel),
                        tint = GeoVaultColorTokens.PrimaryBlue,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            val latLon = String.format(Locale.US, "%.4f, %.4f", model.latitude, model.longitude)
            Text(
                text = latLon,
                style = MaterialTheme.typography.body2,
                color = GeoVaultColorTokens.TextSecondary,
                modifier = Modifier.clickable {
                    clipboardHelper.copyText(latLon, label = "Coordinates")
                },
            )
            val lastUpdatedText = formatLegacyLastUpdatedText(lastUpdatedMs = model.lastUpdatedMs)
            Text(
                text = lastUpdatedText,
                style = MaterialTheme.typography.caption,
                color = GeoVaultColorTokens.TextSecondary,
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
                                    colorFilter = ColorFilter.tint(GeoVaultColorTokens.PrimaryBlue),
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
                                colorFilter = ColorFilter.tint(GeoVaultColorTokens.PrimaryBlue),
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
                                tint = GeoVaultColorTokens.PrimaryBlue,
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
private fun formatLegacyLastUpdatedText(lastUpdatedMs: Long?): String {
    if (lastUpdatedMs == null) return stringResource(R.string.waiting_for_data)
    val diffMs = System.currentTimeMillis() - lastUpdatedMs
    val diffSec = (diffMs / 1000).coerceAtLeast(0)
    val (value, unit) = when {
        diffSec < 60 -> {
            val n = diffSec.toInt()
            val unit = if (n == 1) {
                stringResource(R.string.map_updated_sec)
            } else {
                stringResource(R.string.map_updated_secs)
            }
            n to unit
        }
        diffSec < 3600 -> {
            val n = (diffSec / 60).toInt()
            val unit = if (n == 1) {
                stringResource(R.string.map_updated_min)
            } else {
                stringResource(R.string.map_updated_mins)
            }
            n to unit
        }
        diffSec < 86400 -> {
            val n = (diffSec / 3600).toInt()
            val unit = if (n == 1) {
                stringResource(R.string.map_updated_hr)
            } else {
                stringResource(R.string.map_updated_hrs)
            }
            n to unit
        }
        else -> {
            val n = (diffSec / 86400).toInt()
            val unit = if (n == 1) {
                stringResource(R.string.map_updated_day_short)
            } else {
                stringResource(R.string.map_updated_days_short)
            }
            n to unit
        }
    }
    return stringResource(R.string.map_updated_ago, value, unit)
}

private fun selectNearestFeature(
    map: MapLibreMap,
    tapPoint: PointF,
    features: List<org.maplibre.geojson.Feature>,
): org.maplibre.geojson.Feature? {
    if (features.isEmpty()) return null
    if (features.size == 1) return features[0]
    return features.minByOrNull { feature ->
        val geom = feature.geometry()
        if (geom !is org.maplibre.geojson.Point) return@minByOrNull Float.MAX_VALUE
        val screen = map.projection.toScreenLocation(
            org.maplibre.android.geometry.LatLng(geom.latitude(), geom.longitude())
        )
        val dx = screen.x - tapPoint.x
        val dy = screen.y - tapPoint.y
        kotlin.math.sqrt(dx * dx + dy * dy)
    } ?: features[0]
}

