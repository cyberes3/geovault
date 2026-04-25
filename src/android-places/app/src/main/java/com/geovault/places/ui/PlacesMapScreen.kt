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
import androidx.compose.foundation.layout.size
import androidx.compose.material.Card
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.geovault.common.maps.core.GeoVaultMainMap
import com.geovault.common.maps.core.GeoVaultMainMapView
import com.geovault.common.maps.core.animateCameraToFitLatLngBounds
import com.geovault.common.maps.core.geoVaultLatLngBoundsUnion
import com.geovault.common.maps.core.moveCameraToFitLatLngBounds
import com.geovault.common.maps.core.rememberGeoVaultMapBoundsFitPaddingPx
import com.geovault.common.maps.location.rememberGeoVaultMapUserLocationPlugin
import com.geovault.common.maps.render.GeoJsonRenderPlugin
import com.geovault.common.maps.render.GeoJsonRenderConfig
import com.geovault.common.maps.ui.GeoVaultMapFabColumn
import com.geovault.common.maps.ui.GeoVaultMapFabIcon
import com.geovault.common.maps.ui.buildGeoVaultMapFabActions
import com.geovault.common.maps.ui.geoVaultLayerToggleFabAction
import com.geovault.common.maps.ui.geoVaultZoomInFabAction
import com.geovault.common.maps.ui.geoVaultZoomOutFabAction
import com.geovault.common.maps.ui.recenter.rememberGeoVaultGpsRecenterFabAction
import com.geovault.common.ui.components.GeoVaultPrimaryButton
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.components.GeoVaultTopBarMenuVisibility
import com.geovault.common.ui.components.GeoVaultTopBarSettingsMenuAction
import com.geovault.common.ui.components.GeoVaultTopTitleBar
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.places.R
import com.geovault.places.model.Feature
import com.geovault.places.presentation.PlacesMapViewModel
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng

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
    onOpenSettings: () -> Unit,
    onOpenEdit: (Feature) -> Unit,
    onViewInList: (Feature) -> Unit,
    onNavigate: (Feature) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val rootView = LocalView.current
    val density = LocalDensity.current
    val boundsFitPaddingPx = rememberGeoVaultMapBoundsFitPaddingPx()
    val renderPlugin = remember {
        GeoJsonRenderPlugin(
            config = GeoJsonRenderConfig(
                showPointCircles = false,
                showPointLabelsAndIcons = true,
                showPointTextLabels = false,
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

    LaunchedEffect(Unit) {
        viewModel.loadFromCache()
    }

    DisposableEffect(map) {
        map.registerPlugin(renderPlugin)
        map.registerPlugin(locationPlugin)
        onDispose {
            map.unregisterPlugin(renderPlugin)
            map.unregisterPlugin(locationPlugin)
        }
    }

    DisposableEffect(map, density, state.features) {
        val tapListener = org.maplibre.android.maps.MapLibreMap.OnMapClickListener { tapLatLng ->
            val mapLibreMap = map.maplibreMap ?: return@OnMapClickListener false
            val hitRadiusPx = with(density) { 20.dp.toPx() }
            val near = viewModel.findFeaturesNearTap(tapLatLng, mapLibreMap.projection, hitRadiusPx)
            when {
                near.isEmpty() -> false
                near.size == 1 -> {
                    viewModel.setSelectedFeature(near.first())
                    true
                }
                else -> {
                    val point = mapLibreMap.projection.toScreenLocation(tapLatLng)
                    val popupAnchor = map.getMapViewOrNull() ?: rootView
                    com.geovault.common.maps.ui.OverlappingPointsPopup(
                        context = context,
                        anchor = popupAnchor,
                        pointNames = near.map { it.properties.name.orEmpty() },
                        tapX = point.x.toInt(),
                        tapY = point.y.toInt(),
                    ) { index ->
                        viewModel.setSelectedFeature(near.getOrNull(index))
                    }.show()
                    true
                }
            }
        }
        map.addOnMapClickListener(tapListener)
        onDispose {
            map.removeOnMapClickListener(tapListener)
        }
    }

    LaunchedEffect(state.features, state.selectedFeature) {
        renderPlugin.setRenderState(viewModel.buildMapRenderState())
    }

    val phase by map.phase.collectAsState()
    LaunchedEffect(phase, state.features, launchArgs) {
        map.maplibreMap ?: return@LaunchedEffect
        if (phase != com.geovault.common.maps.core.GeoVaultMapPhase.Ready) return@LaunchedEffect
        if (launchArgs.zoomToId != null && launchArgs.zoomToId >= 0) {
            viewModel.selectByDatabaseId(launchArgs.zoomToId)
        }
        if (!viewModel.shouldApplyInitialCamera(launchArgs.requestToken)) return@LaunchedEffect
        viewModel.markInitialCameraApplied(launchArgs.requestToken)
        if (launchArgs.zoomToLat != null && launchArgs.zoomToLon != null) {
            val camera = CameraPosition.Builder()
                .target(org.maplibre.android.geometry.LatLng(launchArgs.zoomToLat, launchArgs.zoomToLon))
                .zoom(com.geovault.common.maps.core.MapLibreManager.DEFAULT_POINT_ZOOM)
                .build()
            map.moveCameraWithPadding(CameraUpdateFactory.newCameraPosition(camera))
            return@LaunchedEffect
        }
        val bounds = viewModel.featureBounds()
        if (bounds != null) {
            map.moveCameraToFitLatLngBounds(bounds, boundsFitPaddingPx)
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
                        visibility = GeoVaultTopBarMenuVisibility.Always,
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
                .background(GeoVaultColorTokens.ListBackground),
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

            val mapFabActions = buildGeoVaultMapFabActions {
                action(
                    id = "source",
                    order = 10,
                    icon = layerFabAction.icon,
                    contentDescription = "Toggle map source",
                    onTap = layerFabAction.onTap,
                )
                action(
                    id = "home",
                    order = 20,
                    icon = GeoVaultMapFabIcon.Vector(Icons.Default.Home),
                    contentDescription = "Home extent",
                    onTap = {
                        val mapLibreMap = map.maplibreMap
                        if (mapLibreMap != null) {
                            mapLibreMap.setCameraPosition(
                                CameraPosition.Builder(mapLibreMap.cameraPosition).bearing(0.0).tilt(0.0).build()
                            )
                        }
                        val bounds = viewModel.featureBounds()
                        val gpsAnchor = gpsHomeAnchor
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
                    onTap = gpsFabAction.onTap,
                )
                action(
                    id = "zoom_in",
                    order = 40,
                    icon = zoomInFabAction.icon,
                    contentDescription = "Zoom in",
                    onTap = zoomInFabAction.onTap,
                )
                action(
                    id = "zoom_out",
                    order = 50,
                    icon = zoomOutFabAction.icon,
                    contentDescription = "Zoom out",
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

            val selectedFeature = state.selectedFeature
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.ui.graphics.RectangleShape,
                backgroundColor = GeoVaultColorTokens.ListBackground,
                elevation = 0.dp,
            ) {
                Column {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(GeoVaultColorTokens.BorderLight),
                    )
                    Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = viewModel.selectedFeatureLabel(selectedFeature?.properties),
                        color = GeoVaultColorTokens.TextPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        GeoVaultPrimaryButton(
                            text = "View in List",
                            onClick = { selectedFeature?.let(onViewInList) },
                            enabled = selectedFeature?.properties?.database_id != null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            GeoVaultSecondaryButton(
                                text = "Edit",
                                onClick = { selectedFeature?.let(onOpenEdit) },
                                enabled = selectedFeature?.properties?.database_id != null,
                                modifier = Modifier.weight(1f),
                            )
                            GeoVaultSecondaryButton(
                                text = "Navigate",
                                onClick = { selectedFeature?.let(onNavigate) },
                                enabled = selectedFeature?.properties?.database_id != null,
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
