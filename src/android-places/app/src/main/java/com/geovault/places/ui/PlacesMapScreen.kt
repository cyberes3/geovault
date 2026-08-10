package com.geovault.places.ui

import android.location.Location
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
import androidx.compose.foundation.layout.size
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
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
import com.geovault.common.maps.core.animateCameraToFitLatLngBounds
import com.geovault.common.maps.core.geoVaultLatLngBoundsUnion
import com.geovault.common.maps.core.latLngOrNull
import com.geovault.common.maps.core.moveCameraToFitLatLngBounds
import com.geovault.common.maps.core.rememberGeoVaultMapBoundsFitPaddingPx
import com.geovault.common.maps.location.LocationUpdates
import com.geovault.common.maps.location.rememberGeoVaultMapLocationPermissionState
import com.geovault.common.maps.location.rememberGeoVaultMapUserLocationPlugin
import com.geovault.common.maps.render.GeoJsonRenderPlugin
import com.geovault.common.maps.render.GeoJsonRenderConfig
import com.geovault.common.maps.render.GeoVaultRenderedMapHitKind
import com.geovault.common.maps.ui.GeoVaultMapFabColumn
import com.geovault.common.maps.ui.GeoVaultMapFabIcon
import com.geovault.common.maps.ui.buildGeoVaultMapFabActions
import com.geovault.common.maps.ui.geoVaultLayerToggleFabAction
import com.geovault.common.maps.ui.camerafollow.rememberGeoVaultMapHeadingFollowFabBundle
import com.geovault.common.maps.ui.geoVaultZoomInFabAction
import com.geovault.common.maps.ui.geoVaultZoomOutFabAction
import com.geovault.common.maps.ui.lifecycle.GeoVaultMapUserLocationNavigationLifecycle
import com.geovault.common.maps.ui.location.rememberGeoVaultMapLocationSession
import com.geovault.common.ui.components.GeoVaultPrimaryButton
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.components.GeoVaultTopBarMenuVisibility
import com.geovault.common.ui.components.GeoVaultTopBarSettingsMenuAction
import com.geovault.common.ui.components.GeoVaultTopTitleBar
import com.geovault.common.ui.components.TopBarMenuEntry
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.theme.geoVaultContentSecondaryColor
import com.geovault.common.ui.theme.geoVaultHairlineDividerColor
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
    isTabVisible: Boolean = true,
    onOpenSettings: () -> Unit,
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
    LaunchedEffect(locationPlugin, locationSession.decision.shouldStreamGps) {
        if (!locationSession.decision.shouldStreamGps) return@LaunchedEffect
        val latLng = LocationUpdates.getCurrentLatLngOnce(context, timeoutMs = 4000L) ?: return@LaunchedEffect
        val synthetic = Location("places-map-prime").apply {
            latitude = latLng.latitude
            longitude = latLng.longitude
            accuracy = 12f
            time = System.currentTimeMillis()
        }
        locationPlugin.renderLocation(synthetic)
    }
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

    LaunchedEffect(phase, state.features, launchArgs) {
        map.maplibreMap ?: return@LaunchedEffect
        if (phase != GeoVaultMapPhase.Ready) return@LaunchedEffect
        val requestedId = launchArgs.zoomToId?.takeIf { it >= 0 }
        if (requestedId != null &&
            state.features.none { it.properties.database_id == requestedId }
        ) {
            return@LaunchedEffect
        }
        if (!viewModel.shouldApplyInitialCamera(launchArgs.requestToken)) return@LaunchedEffect
        viewModel.markInitialCameraApplied(launchArgs.requestToken)
        if (requestedId != null) {
            viewModel.selectByDatabaseId(requestedId)
        }
        if (launchArgs.zoomToLat != null && launchArgs.zoomToLon != null) {
            val zoomTarget = latLngOrNull(launchArgs.zoomToLat, launchArgs.zoomToLon)
            if (zoomTarget != null) {
                val camera = CameraPosition.Builder()
                    .target(zoomTarget)
                    .zoom(com.geovault.common.maps.core.MapLibreManager.DEFAULT_POINT_ZOOM)
                    .build()
                map.moveCameraWithPadding(CameraUpdateFactory.newCameraPosition(camera))
                if (launchArgs.requestToken != 0L) {
                    onLaunchArgsConsumed()
                }
                return@LaunchedEffect
            }
        }
        val bounds = viewModel.featureBounds()
        if (bounds != null) {
            map.moveCameraToFitLatLngBounds(bounds, boundsFitPaddingPx)
        }
        if (launchArgs.requestToken != 0L) {
            onLaunchArgsConsumed()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            GeoVaultTopTitleBar(
                title = stringResource(R.string.app_title_bar),
                subtitle = stringResource(R.string.map_screen_subtitle),
                actionsContent = {
                    GeoVaultTopBarSettingsMenuAction(
                        onOpenSettings = onOpenSettings,
                        extraEntries = listOf(
                            TopBarMenuEntry(label = "Share", onClick = onOpenShare),
                        ),
                        visibility = GeoVaultTopBarMenuVisibility.Always,
                        overflowTooltip = stringResource(R.string.tooltip_nav_settings),
                    )
                },
            )
        },
    ) { scaffoldPadding ->
        // Match previous activity_map.xml behavior: map lives in a weighted region above the info panel;
        // bottom UI is a sibling, not an overlay — no camera bottom inset needed for it.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
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
                        val mapLibreMap = map.maplibreMap
                        if (mapLibreMap != null) {
                            mapLibreMap.setCameraPosition(
                                CameraPosition.Builder(mapLibreMap.cameraPosition).bearing(0.0).tilt(0.0).build()
                            )
                        }
                        val bounds = viewModel.featureBounds()
                        val gpsAnchor = locationPlugin.getLastLocation()?.let { loc ->
                            latLngOrNull(loc.latitude, loc.longitude)
                        }
                        val effectiveBounds = when {
                            bounds != null && gpsAnchor != null ->
                                geoVaultLatLngBoundsUnion(bounds, listOf(gpsAnchor))
                            else -> bounds
                        }
                        if (effectiveBounds != null) {
                            map.animateCameraToFitLatLngBounds(effectiveBounds, boundsFitPaddingPx)
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
            }

            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(geoVaultHairlineDividerColor()),
            )

            val selectedFeature = state.selectedFeature
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.ui.graphics.RectangleShape,
                backgroundColor = MaterialTheme.colors.background,
                elevation = 0.dp,
            ) {
                Column {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(geoVaultHairlineDividerColor()),
                    )
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
    }
}

private const val PLACES_GPS_STREAM_INTERVAL_MS = 2_000L
