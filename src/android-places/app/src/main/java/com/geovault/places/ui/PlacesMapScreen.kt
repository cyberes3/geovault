package com.geovault.places.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.geovault.common.maps.camera.GeoVaultMapCameraController
import com.geovault.common.maps.core.GeoVaultMainMap
import com.geovault.common.maps.core.GeoVaultMainMapView
import com.geovault.common.maps.core.GeoVaultMapPaddingPolicy
import com.geovault.common.maps.core.GeoVaultMapPhase
import com.geovault.common.maps.core.MapLibreManager
import com.geovault.common.maps.core.latLngOrNull
import com.geovault.common.maps.location.GeoVaultMapPuckOverlapEffect
import com.geovault.common.maps.location.rememberGeoVaultMapLocationPermissionState
import com.geovault.common.maps.location.rememberGeoVaultMapUserLocationPlugin
import com.geovault.common.maps.render.GeoJsonRenderPlugin
import com.geovault.common.maps.render.GeoJsonRenderConfig
import com.geovault.common.maps.render.GeoJsonSelectionOverlayConfig
import com.geovault.common.maps.render.GeoVaultRenderedMapHitKind
import com.geovault.common.maps.ui.GeoVaultMapBottomActionPanel
import com.geovault.common.maps.ui.GeoVaultMapFabColumn
import com.geovault.common.maps.ui.GeoVaultMapFabIcon
import com.geovault.common.maps.ui.GeoVaultMapInitialFrameShield
import com.geovault.common.maps.ui.GeoVaultMapLocationPrimeEffect
import com.geovault.common.maps.ui.buildGeoVaultMapFabActions
import com.geovault.common.maps.ui.geoVaultLayerToggleFabAction
import com.geovault.common.maps.ui.camerafollow.rememberGeoVaultMapHeadingFollowFabBundle
import com.geovault.common.maps.ui.geoVaultZoomInFabAction
import com.geovault.common.maps.ui.geoVaultZoomOutFabAction
import com.geovault.common.maps.ui.lifecycle.GeoVaultMapUserLocationNavigationLifecycle
import com.geovault.common.maps.ui.location.rememberGeoVaultMapLocationSession
import com.geovault.common.ui.GeoVaultAuthShellState
import com.geovault.common.ui.GeoVaultKeepScreenOn
import com.geovault.common.ui.GeoVaultTabShell
import com.geovault.common.ui.components.GeoVaultPrimaryButton
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.components.TopBarMenuEntry
import com.geovault.common.ui.theme.geoVaultContentSecondaryColor
import com.geovault.places.R
import com.geovault.places.model.Place
import com.geovault.places.model.PlaceKey
import com.geovault.places.presentation.PlacesMapLaunchArgs
import com.geovault.places.presentation.PlacesMapViewModel

@Composable
fun PlacesMapScreen(
    map: GeoVaultMainMap,
    viewModel: PlacesMapViewModel,
    launchArgs: PlacesMapLaunchArgs,
    selectedKey: PlaceKey?,
    auth: GeoVaultAuthShellState,
    isTabVisible: Boolean = true,
    onOpenShare: () -> Unit,
    onSelectKey: (PlaceKey?) -> Unit,
    onOpenEdit: (Place) -> Unit,
    onViewInList: (Place) -> Unit,
    onNavigate: (Place) -> Unit,
    onViewDescription: (Place) -> Unit,
    onLaunchArgsConsumed: () -> Unit = {},
) {
    val places by viewModel.places.collectAsState()
    val selectedPlace = remember(places, selectedKey) {
        places.firstOrNull { it.key == selectedKey }
    }
    val context = LocalContext.current
    val density = LocalDensity.current
    val paddingPolicy = remember { GeoVaultMapPaddingPolicy(includeDefaultFabColumnPadding = true) }
    val boundsFitPaddingPx = remember(density) { paddingPolicy.computeBoundsFitPaddingPx(density) }
    val cameraController = remember(map) { GeoVaultMapCameraController(map) }
    val renderPlugin = remember {
        GeoJsonRenderPlugin(
            sourceIdPrefix = "places-main-map",
            config = GeoJsonRenderConfig(
                showPointCircles = false,
                showPointLabelsAndIcons = true,
                showPointTextLabels = true,
                synchronousGeoJsonApplication = true,
                selectionOverlay = GeoJsonSelectionOverlayConfig(),
            ),
            context = context,
        )
    }
    val locationPlugin = rememberGeoVaultMapUserLocationPlugin(context = context)
    val phase by map.phase.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    var isLifecycleStarted by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            isLifecycleStarted = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    GeoVaultKeepScreenOn(enabled = isTabVisible)
    val isActive = isLifecycleStarted && isTabVisible
    val hasLocationPermissionState = rememberGeoVaultMapLocationPermissionState()
    val hasLocationPermission by hasLocationPermissionState
    val headingFollowFabs = rememberGeoVaultMapHeadingFollowFabBundle(
        map = map,
        userLocation = locationPlugin,
        allowFollowCamera = phase == GeoVaultMapPhase.Ready && isTabVisible,
    )
    val locationSession = rememberGeoVaultMapLocationSession(
        headingFollowFabs = headingFollowFabs,
        hasLocationPermission = hasLocationPermission,
        isMapReady = phase == GeoVaultMapPhase.Ready,
        isActive = isActive,
    )
    val gpsFabAction = locationSession.gpsFabAction
    val orientationFabAction = locationSession.headingFabAction
    GeoVaultMapUserLocationNavigationLifecycle(
        userLocation = locationPlugin,
        shouldStreamGps = locationSession.decision.shouldStreamGps,
        shouldEnablePuck = locationSession.decision.shouldEnablePuck,
        showAccuracyCircle = remember(locationPlugin) { locationPlugin.isAccuracyCircleVisible() },
        gpsIntervalMs = PLACES_GPS_STREAM_INTERVAL_MS,
    )
    GeoVaultMapLocationPrimeEffect(
        location = locationPlugin,
        shouldStreamGps = locationSession.decision.shouldStreamGps,
        providerName = "places-map-prime",
    )
    GeoVaultMapPuckOverlapEffect(
        map = map,
        plugin = locationPlugin,
        iconLayerIds = renderPlugin.puckOverlapIconLayerIds(),
    )
    val layerFabAction = remember(map) { geoVaultLayerToggleFabAction(map) }
    val zoomInFabAction = remember(map) { geoVaultZoomInFabAction(map) }
    val zoomOutFabAction = remember(map) { geoVaultZoomOutFabAction(map) }

    renderPlugin.renderedMapTapHitKinds = setOf(GeoVaultRenderedMapHitKind.Point)
    renderPlugin.onRenderedMapHitSelected = { hit ->
        onSelectKey(PlaceKey(hit.id))
        true
    }
    renderPlugin.onRenderedMapBackgroundTapped = {
        onSelectKey(null)
        true
    }

    DisposableEffect(map) {
        map.registerPlugin(renderPlugin)
        map.registerPlugin(locationPlugin)
        onDispose {
            renderPlugin.onRenderedMapHitSelected = null
            renderPlugin.onRenderedMapBackgroundTapped = null
            map.unregisterPlugin(renderPlugin)
            map.unregisterPlugin(locationPlugin)
        }
    }

    LaunchedEffect(places) {
        renderPlugin.setRenderState(viewModel.buildMapRenderState())
    }
    LaunchedEffect(selectedKey) {
        renderPlugin.setSelectedPointId(selectedKey?.value)
    }

    var mapInitialFrameReady by remember { mutableStateOf(false) }
    LaunchedEffect(phase, places, launchArgs) {
        map.maplibreMap ?: return@LaunchedEffect
        if (phase != GeoVaultMapPhase.Ready) return@LaunchedEffect
        val requestedKey = launchArgs.selectKey
        if (requestedKey != null && places.none { it.key == requestedKey }) {
            return@LaunchedEffect
        }
        if (!viewModel.shouldApplyInitialCamera(launchArgs.requestToken)) {
            mapInitialFrameReady = true
            return@LaunchedEffect
        }
        viewModel.markInitialCameraApplied(launchArgs.requestToken)
        if (requestedKey != null) {
            onSelectKey(requestedKey)
        }
        headingFollowFabs.runProgrammaticCamera {
            if (launchArgs.zoomLatitude != null && launchArgs.zoomLongitude != null) {
                val zoomTarget = latLngOrNull(launchArgs.zoomLatitude, launchArgs.zoomLongitude)
                if (zoomTarget != null) {
                    cameraController.focusPointAtZoom(
                        zoomTarget.latitude,
                        zoomTarget.longitude,
                        MapLibreManager.DEFAULT_POINT_ZOOM,
                        animate = false,
                    )
                    return@runProgrammaticCamera
                }
            }
            val bounds = viewModel.featureBounds()
            if (bounds != null) {
                cameraController.fitLatLngBounds(bounds, boundsFitPaddingPx, animate = false)
            }
        }
        mapInitialFrameReady = true
        if (launchArgs.requestToken != 0L) {
            onLaunchArgsConsumed()
        }
    }

    GeoVaultTabShell(
        title = stringResource(R.string.app_title_bar),
        auth = auth,
        modifier = Modifier.fillMaxSize(),
        subtitle = stringResource(R.string.map_screen_subtitle),
        settingsOverflowTooltip = stringResource(R.string.tooltip_nav_settings),
        extraTopBarEntries = listOf(
            TopBarMenuEntry(label = "Share", onClick = onOpenShare),
        ),
        scrollAuthenticatedMainContent = false,
        authenticatedContentHorizontalPadding = 0.dp,
        authenticatedBottomSpacer = 0.dp,
        authenticatedMainContent = {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colors.background),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .fillMaxSize(),
                ) {
                    GeoVaultMainMapView(
                        modifier = Modifier.fillMaxSize(),
                        map = map,
                        showDefaultSourceToggle = false,
                        includeDefaultFabColumnPadding = true,
                    )

                    val layersTooltip = stringResource(R.string.tooltip_map_layers)
                    val fitContentTooltip = stringResource(R.string.tooltip_map_fit_content)
                    val zoomInTooltip = stringResource(R.string.tooltip_map_zoom_in)
                    val zoomOutTooltip = stringResource(R.string.tooltip_map_zoom_out)
                    val mapFabActions = buildGeoVaultMapFabActions {
                        action(
                            id = "source",
                            order = 10,
                            icon = layerFabAction.icon,
                            contentDescription = layersTooltip,
                            tooltip = layersTooltip,
                            onTap = layerFabAction.onTap,
                        )
                        action(
                            id = "home",
                            order = 20,
                            icon = GeoVaultMapFabIcon.Vector(Icons.Default.Home),
                            contentDescription = fitContentTooltip,
                            tooltip = fitContentTooltip,
                            onTap = {
                                headingFollowFabs.runProgrammaticCamera {
                                    cameraController.animateHomeFit(
                                        bounds = viewModel.featureBounds(),
                                        gpsAnchor = locationPlugin.getLastLocation()?.let {
                                            latLngOrNull(it.latitude, it.longitude)
                                        },
                                        paddingPx = boundsFitPaddingPx,
                                    )
                                }
                            },
                        )
                        action(
                            id = gpsFabAction.id,
                            order = gpsFabAction.order,
                            icon = gpsFabAction.icon,
                            contentDescription = gpsFabAction.contentDescription,
                            tooltip = gpsFabAction.contentDescription,
                            onTap = gpsFabAction.onTap,
                        )
                        action(
                            id = orientationFabAction.id,
                            order = orientationFabAction.order,
                            icon = orientationFabAction.icon,
                            contentDescription = orientationFabAction.contentDescription,
                            onTap = orientationFabAction.onTap,
                            tooltip = orientationFabAction.tooltip,
                            iconRotationDegrees = orientationFabAction.iconRotationDegrees,
                            useIntrinsicIconColors = orientationFabAction.useIntrinsicIconColors,
                        )
                        action(
                            id = "zoom_in",
                            order = 40,
                            icon = zoomInFabAction.icon,
                            contentDescription = zoomInTooltip,
                            tooltip = zoomInTooltip,
                            onTap = zoomInFabAction.onTap,
                        )
                        action(
                            id = "zoom_out",
                            order = 50,
                            icon = zoomOutFabAction.icon,
                            contentDescription = zoomOutTooltip,
                            tooltip = zoomOutTooltip,
                            onTap = zoomOutFabAction.onTap,
                        )
                    }

                    GeoVaultMapFabColumn(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 16.dp, end = 16.dp),
                        actions = mapFabActions,
                    )
                    GeoVaultMapInitialFrameShield(
                        visible = !mapInitialFrameReady,
                        statusText = "Loading map",
                    )
                }

                GeoVaultMapBottomActionPanel {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = viewModel.selectedPlaceLabel(selectedPlace),
                            color = MaterialTheme.colors.onSurface,
                            fontWeight = FontWeight.Bold,
                        )
                        val description = selectedPlace?.content?.description?.takeIf { it.isNotBlank() }
                        Text(
                            text = description ?: "No description",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .clickable(enabled = description != null) {
                                    selectedPlace?.let(onViewDescription)
                                },
                            color = geoVaultContentSecondaryColor(),
                            fontStyle = if (description == null) FontStyle.Italic else FontStyle.Normal,
                            minLines = 1,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            GeoVaultPrimaryButton(
                                text = "View in List",
                                onClick = { selectedPlace?.let(onViewInList) },
                                enabled = selectedPlace != null,
                                tooltip = stringResource(R.string.tooltip_map_view_in_list),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                GeoVaultSecondaryButton(
                                    text = "Edit",
                                    onClick = { selectedPlace?.let(onOpenEdit) },
                                    enabled = selectedPlace != null,
                                    tooltip = stringResource(R.string.tooltip_place_edit),
                                    modifier = Modifier.weight(1f),
                                )
                                GeoVaultSecondaryButton(
                                    text = "Navigate",
                                    onClick = { selectedPlace?.let(onNavigate) },
                                    enabled = selectedPlace != null,
                                    tooltip = stringResource(R.string.tooltip_place_navigate),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

private const val PLACES_GPS_STREAM_INTERVAL_MS = 2_000L
