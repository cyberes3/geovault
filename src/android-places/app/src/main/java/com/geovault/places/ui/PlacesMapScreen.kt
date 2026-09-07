package com.geovault.places.ui

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
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.geovault.common.maps.core.GeoVaultMainMap
import com.geovault.common.maps.core.GeoVaultMainMapView
import com.geovault.common.maps.core.GeoVaultMapPhase
import com.geovault.common.maps.core.animateCameraToHomeFit
import com.geovault.common.maps.core.latLngOrNull
import com.geovault.common.maps.core.moveCameraToFitLatLngBounds
import com.geovault.common.maps.core.rememberGeoVaultMapBoundsFitPaddingPx
import com.geovault.common.maps.location.rememberGeoVaultMapLocationPermissionState
import com.geovault.common.maps.location.rememberGeoVaultMapUserLocationPlugin
import com.geovault.common.maps.render.GeoJsonRenderPlugin
import com.geovault.common.maps.render.GeoJsonRenderConfig
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
import com.geovault.common.ui.GeoVaultTabShell
import com.geovault.common.ui.components.GeoVaultPrimaryButton
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.components.TopBarMenuEntry
import com.geovault.common.ui.theme.geoVaultContentSecondaryColor
import com.geovault.places.R
import com.geovault.places.model.Feature
import com.geovault.places.presentation.PlacesMapViewModel
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory

data class PlacesMapLaunchArgs(
    val zoomToLat: Double? = null,
    val zoomToLon: Double? = null,
    val zoomToId: Int? = null,
    val requestToken: Long = 0L,
)

@Composable
fun PlacesMapScreen(
    map: GeoVaultMainMap,
    viewModel: PlacesMapViewModel,
    launchArgs: PlacesMapLaunchArgs,
    auth: GeoVaultAuthShellState,
    isTabVisible: Boolean = true,
    onOpenShare: () -> Unit,
    onOpenEdit: (Feature) -> Unit,
    onViewInList: (Feature) -> Unit,
    onNavigate: (Feature) -> Unit,
    onViewDescription: (Feature) -> Unit,
    onLaunchArgsConsumed: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val boundsFitPaddingPx = rememberGeoVaultMapBoundsFitPaddingPx()
    val renderPlugin = remember {
        GeoJsonRenderPlugin(
            config = GeoJsonRenderConfig(
                showPointCircles = false,
                showPointLabelsAndIcons = true,
                showPointTextLabels = true,
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
    val layerFabAction = remember(map) { geoVaultLayerToggleFabAction(map) }
    val zoomInFabAction = remember(map) { geoVaultZoomInFabAction(map) }
    val zoomOutFabAction = remember(map) { geoVaultZoomOutFabAction(map) }

    renderPlugin.renderedMapTapHitKinds = setOf(GeoVaultRenderedMapHitKind.Point)
    renderPlugin.onRenderedMapHitSelected = { hit ->
        viewModel.selectByRenderId(hit.id)
    }
    renderPlugin.onRenderedMapBackgroundTapped = {
        viewModel.setSelectedFeature(null)
        false
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

    LaunchedEffect(state.features, state.selectedFeature) {
        renderPlugin.setRenderState(viewModel.buildMapRenderState())
    }

    var mapInitialFrameReady by remember { mutableStateOf(false) }
    LaunchedEffect(phase, state.features, launchArgs) {
        map.maplibreMap ?: return@LaunchedEffect
        if (phase != GeoVaultMapPhase.Ready) return@LaunchedEffect
        val requestedId = launchArgs.zoomToId?.takeIf { it >= 0 }
        if (requestedId != null &&
            state.features.none { it.properties.database_id == requestedId }
        ) {
            return@LaunchedEffect
        }
        if (!viewModel.shouldApplyInitialCamera(launchArgs.requestToken)) {
            mapInitialFrameReady = true
            return@LaunchedEffect
        }
        viewModel.markInitialCameraApplied(launchArgs.requestToken)
        if (requestedId != null) {
            viewModel.selectByDatabaseId(requestedId)
        }
        headingFollowFabs.runProgrammaticCamera {
            if (launchArgs.zoomToLat != null && launchArgs.zoomToLon != null) {
                val zoomTarget = latLngOrNull(launchArgs.zoomToLat, launchArgs.zoomToLon)
                if (zoomTarget != null) {
                    val camera = CameraPosition.Builder()
                        .target(zoomTarget)
                        .zoom(com.geovault.common.maps.core.MapLibreManager.DEFAULT_POINT_ZOOM)
                        .build()
                    map.moveCameraWithPadding(CameraUpdateFactory.newCameraPosition(camera))
                    return@runProgrammaticCamera
                }
            }
            val bounds = viewModel.featureBounds()
            if (bounds != null) {
                map.moveCameraToFitLatLngBounds(bounds, boundsFitPaddingPx)
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
        // Match previous activity_map.xml behavior: map lives in a weighted region above the info panel;
        // bottom UI is a sibling, not an overlay — no camera bottom inset needed for it.
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
                            map.animateCameraToHomeFit(
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

            val selectedFeature = state.selectedFeature
            GeoVaultMapBottomActionPanel {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = viewModel.selectedFeatureLabel(selectedFeature?.properties),
                        color = MaterialTheme.colors.onSurface,
                        fontWeight = FontWeight.Bold,
                    )
                    // Always reserve a line for the description (even when blank) so the card's
                    // height — and the buttons below it — don't jump around as the selected
                    // feature changes.
                    val description = selectedFeature?.properties?.description?.takeIf { it.isNotBlank() }
                    Text(
                        text = description ?: "No description",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .clickable(enabled = description != null) {
                                selectedFeature?.let(onViewDescription)
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
                            onClick = { selectedFeature?.let(onViewInList) },
                            enabled = selectedFeature != null,
                            tooltip = stringResource(R.string.tooltip_map_view_in_list),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            GeoVaultSecondaryButton(
                                text = "Edit",
                                onClick = { selectedFeature?.let(onOpenEdit) },
                                enabled = selectedFeature != null,
                                tooltip = stringResource(R.string.tooltip_place_edit),
                                modifier = Modifier.weight(1f),
                            )
                            GeoVaultSecondaryButton(
                                text = "Navigate",
                                onClick = { selectedFeature?.let(onNavigate) },
                                enabled = selectedFeature != null,
                                tooltip = stringResource(R.string.tooltip_place_navigate),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
        }
    )
}

private const val PLACES_GPS_STREAM_INTERVAL_MS = 2_000L
