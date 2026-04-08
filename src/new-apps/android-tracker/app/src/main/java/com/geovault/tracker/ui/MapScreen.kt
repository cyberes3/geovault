package com.geovault.tracker.ui

import android.graphics.PointF
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
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
import com.geovault.common.maps.core.rememberGeoVaultMainMap
import com.geovault.common.maps.core.rememberGeoVaultMapBoundsFitPaddingPx
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
import com.geovault.common.ui.components.GeoVaultAuthGate
import com.geovault.common.ui.components.GeoVaultLoadingSpinner
import com.geovault.common.ui.components.GeoVaultTopBarSettingsMenuAction
import com.geovault.common.ui.components.GeoVaultTopTitleBar
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.tracker.params.TrackerParamsRouteArgs
import com.geovault.tracker.params.toTrackerParamsRouteArgs
import com.geovault.tracker.R
import com.geovault.tracker.TrackerApplication
import com.geovault.tracker.location.TrackingPermissionGate
import com.geovault.tracker.services.TrackingUiStatus
import com.geovault.tracker.presentation.TrackerMapCameraLockPolicy
import com.geovault.tracker.presentation.TrackerMapDisplayMode
import com.geovault.tracker.presentation.TrackerMapGroupModeOption
import com.geovault.tracker.presentation.TrackerMapSelectionCard
import com.geovault.tracker.presentation.TrackerMapUiState
import com.geovault.tracker.presentation.TrackerMapViewModel
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

@Composable
fun MapScreen(
    mapViewModel: TrackerMapViewModel,
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
        modifier = Modifier.fillMaxSize(),
        topBar = {
            GeoVaultTopTitleBar(
                title = stringResource(R.string.map_screen_title),
                actionsContent = {
                    GeoVaultTopBarSettingsMenuAction(
                        onOpenSettings = onOpenSettings,
                        isAuthenticated = isAuthenticated,
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
                modifier = Modifier.fillMaxSize(),
            ) {
                TrackerMapAuthenticatedContent(
                    map = rememberGeoVaultMainMap(TrackerApplication.TRACKER_MAIN_MAP_KEY),
                    viewModel = mapViewModel,
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
    onHostNavigationRequested: (MapHostNavigationRequest) -> Unit,
    onRequestTrackerParams: (TrackerParamsRouteArgs) -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var locationPermission by remember {
        mutableStateOf(TrackingPermissionGate.hasLocationPermission(context))
    }

    DisposableEffect(viewModel) {
        viewModel.onMapSurfaceVisible()
        onDispose { viewModel.onMapSurfaceHidden() }
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

    val boundsFitPaddingPx = rememberGeoVaultMapBoundsFitPaddingPx()
    val renderPlugin = remember {
        GeoJsonRenderPlugin(
            sourceIdPrefix = "gv-tracker-map",
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

    val phase by map.phase.collectAsState()
    LaunchedEffect(phase) {
        viewModel.setMapReady(phase == GeoVaultMapPhase.Ready)
    }
    DisposableEffect(map, locationPermission, phase, state.runtime.isRunning) {
        val shouldStreamGps = locationPermission &&
            phase == GeoVaultMapPhase.Ready &&
            TrackerMapCameraLockPolicy.shouldRenderUserLocation(state.runtime.isRunning)
        if (shouldStreamGps) {
            locationPlugin.startRenderingGpsLocation(intervalMs = 2000L)
        }
        onDispose {
            if (shouldStreamGps) {
                locationPlugin.stopRenderingGpsLocation()
            }
        }
    }

    LaunchedEffect(state.followLockEnabled, phase) {
        if (phase != GeoVaultMapPhase.Ready) return@LaunchedEffect
        val allowPuck = TrackerMapCameraLockPolicy.shouldRenderUserLocation(state.runtime.isRunning)
        locationPlugin.setEnabled(allowPuck)
        locationPlugin.setCameraTracking(
            TrackerMapCameraLockPolicy.shouldEnableFollowCamera(
                runtimeRunning = state.runtime.isRunning,
                followLockEnabled = state.followLockEnabled
            )
        )
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
            viewModel.clearFollowLockAfterUserGesture()
        }
        map.addOnCameraMoveStartedListener(listener)
        onDispose {
            map.removeOnCameraMoveStartedListener(listener)
        }
    }
    DisposableEffect(map) {
        val clickListener = MapLibreMap.OnMapClickListener { latLng ->
            val maplibreMap = map.maplibreMap ?: return@OnMapClickListener false
            val screenPoint: PointF = maplibreMap.projection.toScreenLocation(latLng)
            val features = runCatching {
                maplibreMap.queryRenderedFeatures(
                    screenPoint,
                    "gv-tracker-map-points-label-layer"
                )
            }.getOrElse { emptyList() }
            val trackId = features.asSequence()
                .mapNotNull { feature ->
                    val id = feature.getStringProperty("id") ?: return@mapNotNull null
                    when {
                        id == "last-fix" -> {
                            state.displayedTrackerId.ifBlank { state.runtime.selectedTrackerId }
                        }
                        id.startsWith("remote-") -> id.removePrefix("remote-")
                        else -> null
                    }?.trim()?.takeIf { it.isNotEmpty() }
                }
                .firstOrNull()
            if (trackId != null) {
                viewModel.selectMapTrackerFromTap(trackId)
                true
            } else {
                false
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
        val renderState = viewModel.buildMapRenderState()
        val resolvedState = markerIconPlugin.resolveRenderStateWithFallback(renderState)
        renderPlugin.setRenderState(resolvedState)
    }

    var didInitialBounds by remember { mutableStateOf(false) }
    LaunchedEffect(phase, state.trail, state.runtime) {
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
                includeDefaultFabColumnPadding = true,
            )

            val followSelected = state.followLockEnabled
            val mapFabActions = buildGeoVaultMapFabActions {
                action(
                    id = "source",
                    order = 10,
                    icon = layerFabAction.icon,
                    contentDescription = fabDescSource,
                    onTap = layerFabAction.onTap,
                )
                action(
                    id = "home_extent",
                    order = 20,
                    icon = GeoVaultMapFabIcon.Vector(Icons.Default.Home),
                    contentDescription = fabDescFitTrail,
                    onTap = {
                        if (phase == GeoVaultMapPhase.Ready) {
                            geoVaultResetCameraBearingAndTilt(map)
                            viewModel.requestFitTrail()
                        }
                    },
                )
                action(
                    id = gpsFabAction.id,
                    order = gpsFabAction.order,
                    icon = gpsFabAction.icon,
                    contentDescription = gpsFabAction.contentDescription,
                    onTap = {
                        viewModel.setFollowLock(false)
                        gpsFabAction.onTap?.invoke()
                    },
                )
                action(
                    id = "follow_lock",
                    order = 25,
                    icon = GeoVaultMapFabIcon.Vector(
                        if (followSelected) Icons.Default.Lock else Icons.Outlined.LockOpen,
                    ),
                    contentDescription = fabDescFollow,
                    onTap = { viewModel.setFollowLock(!followSelected) },
                )
                action(
                    id = "zoom_in",
                    order = 40,
                    icon = zoomInFabAction.icon,
                    contentDescription = fabDescZoomIn,
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

            MapStatusCornerIndicator(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp),
                state = state,
                mapReady = phase == GeoVaultMapPhase.Ready,
            )
            if (state.isHistoryLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colors.surface.copy(alpha = 0.70f))
                        .pointerInteropFilter { true },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        GeoVaultLoadingSpinner(spinnerSize = 20.dp)
                        Text(
                            text = stringResource(R.string.map_status_map_loading),
                            style = MaterialTheme.typography.body2,
                        )
                    }
                }
            }
        }

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
                Text(
                    text = stringResource(R.string.map_mode_section_title),
                    color = GeoVaultColorTokens.TextPrimary,
                    style = MaterialTheme.typography.subtitle2,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(6.dp))
                MapStatusStrip(
                    state = state,
                    mapReady = phase == GeoVaultMapPhase.Ready,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ModeTextButton(
                        label = stringResource(R.string.map_mode_session),
                        selected = state.mode == TrackerMapDisplayMode.SINGLE_SESSION,
                        onClick = { viewModel.setMode(TrackerMapDisplayMode.SINGLE_SESSION) },
                        modifier = Modifier.weight(1f),
                    )
                    ModeTextButton(
                        label = stringResource(R.string.map_mode_all),
                        selected = state.mode == TrackerMapDisplayMode.ALL_QUEUE,
                        onClick = { viewModel.setMode(TrackerMapDisplayMode.ALL_QUEUE) },
                        modifier = Modifier.weight(1f),
                    )
                    ModeTextButton(
                        label = stringResource(R.string.map_mode_group),
                        selected = state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER,
                        onClick = { viewModel.setMode(TrackerMapDisplayMode.GROUP_PLACEHOLDER) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (state.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
                    Spacer(modifier = Modifier.height(8.dp))
                    GroupModeSelector(
                        options = state.groupModeOptions,
                        selectedGroupId = state.currentGroupId,
                        onSelectGroup = viewModel::setGroupModeGroup
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ModeTextButton(
                        label = stringResource(R.string.map_action_view_in_list),
                        selected = false,
                        onClick = {
                            onHostNavigationRequested(
                                MapHostNavigationRequestResolver.fromListNavigationTarget(
                                    viewModel.resolveListNavigationTarget()
                                )
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                    ModeTextButton(
                        label = stringResource(R.string.map_action_view_params),
                        selected = false,
                        onClick = {
                            state.selectedMapTracker
                                ?.toTrackerParamsRouteArgs()
                                ?.let(onRequestTrackerParams)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    ModeTextButton(
                        label = stringResource(R.string.map_action_open_shared),
                        selected = false,
                        onClick = { onHostNavigationRequested(MapHostNavigationRequestResolver.forShared(state)) },
                        modifier = Modifier.weight(1f),
                    )
                }
                val selection = state.selectedMapTracker
                if (selection != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    MapTrackerSelectionPanel(
                        selection = selection,
                        isLocked = state.selectionLockTrackerId == selection.trackerId,
                        onViewInList = {
                            onHostNavigationRequested(
                                MapHostNavigationRequestResolver.fromListNavigationTarget(
                                    viewModel.resolveListNavigationTarget(selection.trackerId)
                                )
                            )
                        },
                        onViewParams = {
                            onRequestTrackerParams(selection.toTrackerParamsRouteArgs())
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

@Composable
private fun MapStatusStrip(
    state: TrackerMapUiState,
    mapReady: Boolean,
) {
    val trackingStatusText = when (state.runtime.uiStatus) {
        TrackingUiStatus.NOT_TRACKING -> stringResource(R.string.tracking_status_not_tracking)
        TrackingUiStatus.WAITING_FOR_GPS -> stringResource(R.string.tracking_status_waiting_for_gps)
        TrackingUiStatus.LOCKING -> stringResource(R.string.tracking_status_locking)
        TrackingUiStatus.TRACKING_ACTIVE -> stringResource(R.string.tracking_status_active)
    }
    val streamingCount = state.activeStreamedTrackerIds.size
    val streamingText = if (streamingCount <= 0) {
        stringResource(R.string.map_status_streaming_off)
    } else {
        stringResource(R.string.map_status_streaming_count, streamingCount)
    }
    val mapStatusText = if (mapReady) {
        stringResource(R.string.map_status_map_ready)
    } else {
        stringResource(R.string.map_status_map_loading)
    }
    val accuracyWarning = shouldShowGpsAccuracyWarning(state)
    val accuracyText = if (accuracyWarning) {
        stringResource(R.string.map_status_accuracy_low)
    } else {
        stringResource(R.string.map_status_accuracy_ok)
    }
    Text(
        text = "$trackingStatusText  ·  $streamingText  ·  $mapStatusText  ·  $accuracyText",
        color = GeoVaultColorTokens.TextSecondary,
        style = MaterialTheme.typography.caption,
    )
}

@Composable
private fun MapStatusCornerIndicator(
    modifier: Modifier = Modifier,
    state: TrackerMapUiState,
    mapReady: Boolean,
) {
    val streaming = state.activeStreamedTrackerIds.isNotEmpty()
    val loading = !mapReady
    val accuracyWarning = shouldShowGpsAccuracyWarning(state)
    if (!streaming && !loading && !accuracyWarning) return
    Surface(
        modifier = modifier,
        color = MaterialTheme.colors.surface.copy(alpha = 0.92f),
        shape = MaterialTheme.shapes.small,
        elevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                loading && streaming -> {
                    GeoVaultLoadingSpinner(spinnerSize = 16.dp)
                    Text(
                        text = stringResource(R.string.map_status_map_loading),
                        style = MaterialTheme.typography.caption,
                    )
                }
                accuracyWarning -> {
                    androidx.compose.material.Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colors.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.map_status_accuracy_low),
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.error,
                    )
                }
                streaming -> {
                    Text(
                        text = "●",
                        color = MaterialTheme.colors.error,
                        fontSize = 13.sp,
                        style = MaterialTheme.typography.caption,
                    )
                    Text(
                        text = stringResource(R.string.map_status_streaming_live),
                        style = MaterialTheme.typography.caption,
                    )
                }
            }
        }
    }
}

private fun shouldShowGpsAccuracyWarning(state: TrackerMapUiState): Boolean {
    if (!state.runtime.isRunning) return false
    val accuracyMeters = state.runtime.lastAccuracyMeters
    val thresholdMeters = state.runtime.effectiveAccuracyThresholdMeters
    return accuracyMeters == null || accuracyMeters > thresholdMeters
}

@Composable
private fun ModeTextButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(onClick = onClick, modifier = modifier) {
        Text(
            text = label,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) GeoVaultColorTokens.PrimaryBlue else GeoVaultColorTokens.TextPrimary,
        )
    }
}

@Composable
private fun GroupModeSelector(
    options: List<TrackerMapGroupModeOption>,
    selectedGroupId: String,
    onSelectGroup: (String) -> Unit,
) {
    if (options.isEmpty()) {
        Text(
            text = stringResource(R.string.map_group_empty_body),
            color = GeoVaultColorTokens.TextSecondary,
            style = MaterialTheme.typography.caption,
        )
        return
    }
    Text(
        text = stringResource(R.string.map_group_picker_title),
        color = GeoVaultColorTokens.TextSecondary,
        style = MaterialTheme.typography.caption,
    )
    Spacer(modifier = Modifier.height(4.dp))
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
    ) {
        items(
            items = options,
            key = { it.groupId }
        ) { option ->
            TextButton(
                onClick = { onSelectGroup(option.groupId) },
            ) {
                Text(
                    text = stringResource(
                        R.string.map_group_picker_option,
                        option.groupName,
                        option.trackerIds.size
                    ),
                    fontWeight = if (selectedGroupId == option.groupId) {
                        FontWeight.Bold
                    } else {
                        FontWeight.Normal
                    },
                    color = if (selectedGroupId == option.groupId) {
                        GeoVaultColorTokens.PrimaryBlue
                    } else {
                        GeoVaultColorTokens.TextPrimary
                    },
                )
            }
        }
    }
}

@Composable
private fun MapTrackerSelectionPanel(
    selection: TrackerMapSelectionCard,
    isLocked: Boolean,
    onViewInList: () -> Unit,
    onViewParams: () -> Unit,
    onFocus: () -> Unit,
    onToggleLock: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colors.surface,
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = selection.trackerName,
                style = MaterialTheme.typography.subtitle2,
                fontWeight = FontWeight.Bold,
            )
            val latLon = stringResource(
                R.string.map_selection_coordinates,
                selection.latitude,
                selection.longitude
            )
            Text(text = latLon, style = MaterialTheme.typography.caption)
            selection.lastUpdatedMs?.let { updatedMs ->
                val ago = DateUtils.getRelativeTimeSpanString(
                    updatedMs,
                    System.currentTimeMillis(),
                    DateUtils.SECOND_IN_MILLIS
                )
                Text(
                    text = stringResource(R.string.map_selection_updated_ago, ago),
                    style = MaterialTheme.typography.caption,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeTextButton(
                    label = stringResource(R.string.map_action_view_in_list),
                    selected = false,
                    onClick = onViewInList,
                    modifier = Modifier.weight(1f),
                )
                ModeTextButton(
                    label = stringResource(R.string.map_action_view_params),
                    selected = false,
                    onClick = onViewParams,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            if (isLocked) {
                Text(
                    text = stringResource(R.string.map_selection_lock_active),
                    style = MaterialTheme.typography.caption,
                    color = GeoVaultColorTokens.PrimaryBlue,
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeTextButton(
                    label = stringResource(R.string.map_action_focus_tracker),
                    selected = false,
                    onClick = onFocus,
                    modifier = Modifier.weight(1f),
                )
                ModeTextButton(
                    label = if (isLocked) {
                        stringResource(R.string.map_action_unlock_selection)
                    } else {
                        stringResource(R.string.map_action_lock_selection)
                    },
                    selected = false,
                    onClick = onToggleLock,
                    modifier = Modifier.weight(1f),
                )
                ModeTextButton(
                    label = stringResource(R.string.trackers_dialog_cancel),
                    selected = false,
                    onClick = onClear,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

