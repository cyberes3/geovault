package com.geovault.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.geovault.common.maps.core.GeoVaultMainMap
import com.geovault.common.maps.core.GeoVaultMainMapView
import com.geovault.common.maps.core.GeoVaultMapPhase
import com.geovault.common.maps.core.animateCameraToFitLatLngBounds
import com.geovault.common.maps.core.geoVaultLatLngBoundsUnion
import com.geovault.common.maps.core.moveCameraToFitLatLngBounds
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
import com.geovault.common.ui.components.GeoVaultTopBarSettingsMenuAction
import com.geovault.common.ui.components.GeoVaultTopTitleBar
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.tracker.R
import com.geovault.tracker.MapStreamingServiceHelper
import com.geovault.tracker.TrackerApplication
import com.geovault.tracker.location.TrackingPermissionGate
import com.geovault.tracker.presentation.TrackerMapDisplayMode
import com.geovault.tracker.presentation.TrackerMapViewModel
import org.maplibre.android.camera.CameraPosition
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
                TrackerMapAuthenticatedContent(map = rememberGeoVaultMainMap(TrackerApplication.TRACKER_MAIN_MAP_KEY), viewModel = mapViewModel)
            }
        }
    }
}

@Composable
private fun TrackerMapAuthenticatedContent(
    map: GeoVaultMainMap,
    viewModel: TrackerMapViewModel,
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
                showPointTextLabels = true,
            ),
            context = context,
        )
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
        map.registerPlugin(locationPlugin)
        onDispose {
            map.unregisterPlugin(renderPlugin)
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
            !state.runtime.isRunning
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
        val allowPuck = !state.runtime.isRunning
        locationPlugin.setEnabled(allowPuck)
        locationPlugin.setCameraTracking(allowPuck && state.followLockEnabled)
    }

    DisposableEffect(map) {
        val listener = MapLibreMap.OnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                viewModel.clearFollowLockAfterUserGesture()
            }
        }
        map.addOnCameraMoveStartedListener(listener)
        onDispose {
            map.removeOnCameraMoveStartedListener(listener)
        }
    }

    LaunchedEffect(state.trail, state.runtime, state.mode, state.remoteLastPoints, state.activeStreamedTrackerIds) {
        renderPlugin.setRenderState(viewModel.buildMapRenderState())
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
                        val mapLibreMap = map.maplibreMap
                        if (mapLibreMap != null) {
                            mapLibreMap.setCameraPosition(
                                CameraPosition.Builder(mapLibreMap.cameraPosition).bearing(0.0).tilt(0.0).build(),
                            )
                        }
                        viewModel.requestFitTrail()
                    },
                )
                action(
                    id = gpsFabAction.id,
                    order = gpsFabAction.order,
                    icon = gpsFabAction.icon,
                    contentDescription = gpsFabAction.contentDescription,
                    onTap = gpsFabAction.onTap,
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
                    onTap = zoomInFabAction.onTap,
                )
                action(
                    id = "zoom_out",
                    order = 50,
                    icon = zoomOutFabAction.icon,
                    contentDescription = fabDescZoomOut,
                    onTap = zoomOutFabAction.onTap,
                )
            }

            GeoVaultMapFabColumn(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 16.dp, end = 16.dp),
                actions = mapFabActions,
            )
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
                    Text(
                        text = stringResource(R.string.map_group_placeholder_body),
                        color = GeoVaultColorTokens.TextSecondary,
                        style = MaterialTheme.typography.caption,
                    )
                }
            }
        }
    }
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
