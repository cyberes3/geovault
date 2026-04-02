package com.geovault.places.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Remove
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.geovault.common.maps.core.GeoVaultMap
import com.geovault.common.maps.core.GeoVaultMapController
import com.geovault.common.maps.core.GeoVaultMapMode
import com.geovault.common.maps.location.LocationComponentHelper
import com.geovault.common.maps.location.LocationUpdates
import com.geovault.common.maps.location.MapLocationRendererPlugin
import com.geovault.common.maps.render.GeoJsonRenderPlugin
import com.geovault.common.maps.render.GeoJsonRenderConfig
import com.geovault.common.maps.ui.GeoVaultMapFabColumn
import com.geovault.common.maps.ui.GeoVaultMapFabIcon
import com.geovault.common.maps.ui.buildGeoVaultMapFabActions
import com.geovault.common.ui.components.GeoVaultPrimaryButton
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.components.GeoVaultTopBarSettingsMenuAction
import com.geovault.common.ui.components.GeoVaultTopTitleBar
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.places.model.Feature
import com.geovault.places.presentation.PlacesMapViewModel
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory

data class PlacesMapLaunchArgs(
    val zoomToLat: Double? = null,
    val zoomToLon: Double? = null,
    val zoomToId: Int? = null,
)

@Composable
fun PlacesMapScreen(
    controller: GeoVaultMapController,
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
    val locationPlugin = remember {
        MapLocationRendererPlugin(
            context = context,
            config = LocationComponentHelper.Config(
                accuracyColor = GeoVaultColorTokens.PRIMARY_BLUE_INT,
                accuracyAlpha = 0.25f,
            ),
            autoEnableLocationComponent = true,
        )
    }
    var initialCameraApplied by remember { mutableStateOf(false) }
    var locationEnabled by remember { mutableStateOf(false) }
    var gpsPanSession by remember { mutableStateOf<LocationUpdates.LocationUpdatesSession?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            locationEnabled = true
            startLocationPanning(context, controller, locationPlugin) { session ->
                gpsPanSession?.stop()
                gpsPanSession = session
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadFromCache()
    }

    DisposableEffect(controller) {
        controller.registerPlugin(renderPlugin)
        controller.registerPlugin(locationPlugin)
        onDispose {
            gpsPanSession?.stop()
            controller.unregisterPlugin(renderPlugin)
            controller.unregisterPlugin(locationPlugin)
        }
    }

    DisposableEffect(controller, density, state.features) {
        val tapListener = org.maplibre.android.maps.MapLibreMap.OnMapClickListener { tapLatLng ->
            val map = controller.maplibreMap ?: return@OnMapClickListener false
            val hitRadiusPx = with(density) { 20.dp.toPx() }
            val near = viewModel.findFeaturesNearTap(tapLatLng, map.projection, hitRadiusPx)
            when {
                near.isEmpty() -> false
                near.size == 1 -> {
                    viewModel.setSelectedFeature(near.first())
                    true
                }
                else -> {
                    val point = map.projection.toScreenLocation(tapLatLng)
                    val popupAnchor = controller.getMapViewOrNull() ?: rootView
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
        controller.addOnMapClickListener(tapListener)
        onDispose {
            controller.removeOnMapClickListener(tapListener)
        }
    }

    LaunchedEffect(state.features, state.selectedFeature) {
        renderPlugin.setRenderState(viewModel.buildMapRenderState())
    }

    val phase by controller.phase.collectAsState()
    LaunchedEffect(phase, state.features, launchArgs) {
        val map = controller.maplibreMap ?: return@LaunchedEffect
        if (phase != com.geovault.common.maps.core.GeoVaultMapPhase.Ready) return@LaunchedEffect
        if (launchArgs.zoomToId != null && launchArgs.zoomToId >= 0) {
            viewModel.selectByDatabaseId(launchArgs.zoomToId)
        }
        if (initialCameraApplied) return@LaunchedEffect
        initialCameraApplied = true
        if (launchArgs.zoomToLat != null && launchArgs.zoomToLon != null) {
            val camera = CameraPosition.Builder()
                .target(org.maplibre.android.geometry.LatLng(launchArgs.zoomToLat, launchArgs.zoomToLon))
                .zoom(com.geovault.common.maps.core.MapLibreManager.DEFAULT_POINT_ZOOM)
                .build()
            controller.moveCameraWithPadding(CameraUpdateFactory.newCameraPosition(camera))
            return@LaunchedEffect
        }
        val bounds = viewModel.featureBounds()
        if (bounds != null) {
            controller.moveCameraWithPadding(CameraUpdateFactory.newLatLngBounds(bounds, 96))
        }
    }

    Scaffold(
        topBar = {
            GeoVaultTopTitleBar(
                title = "Places Map",
                actionsContent = {
                    GeoVaultTopBarSettingsMenuAction(
                        onOpenSettings = onOpenSettings,
                        isAuthenticated = true,
                    )
                },
            )
        },
    ) { scaffoldPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .background(GeoVaultColorTokens.Background),
        ) {
            GeoVaultMap(
                modifier = Modifier.fillMaxSize(),
                controller = controller,
                showDefaultSourceToggle = false,
                mapMode = GeoVaultMapMode.Main,
            )

            val mapFabActions = buildGeoVaultMapFabActions {
                action(
                    id = "source",
                    order = 10,
                    icon = GeoVaultMapFabIcon.Vector(Icons.Default.Layers),
                    contentDescription = "Toggle map source",
                    onTap = { controller.cycleSource() },
                )
                action(
                    id = "home",
                    order = 20,
                    icon = GeoVaultMapFabIcon.Vector(Icons.Default.Home),
                    contentDescription = "Home extent",
                    onTap = {
                        val map = controller.maplibreMap
                        if (map != null) {
                            map.setCameraPosition(CameraPosition.Builder(map.cameraPosition).bearing(0.0).tilt(0.0).build())
                        }
                        val bounds = viewModel.featureBounds()
                        if (bounds != null) {
                            controller.animateCameraWithPadding(CameraUpdateFactory.newLatLngBounds(bounds, 96))
                        }
                    },
                )
                action(
                    id = "gps",
                    order = 30,
                    icon = GeoVaultMapFabIcon.Vector(Icons.Default.GpsFixed),
                    contentDescription = "Enable location",
                    emphasized = locationEnabled,
                    onTap = {
                        val hasLocationPermission =
                            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        if (!hasLocationPermission) {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                        } else {
                            locationEnabled = true
                            startLocationPanning(context, controller, locationPlugin) { session ->
                                gpsPanSession?.stop()
                                gpsPanSession = session
                            }
                        }
                    },
                )
                action(
                    id = "zoom_in",
                    order = 40,
                    icon = GeoVaultMapFabIcon.Vector(Icons.Default.Add),
                    contentDescription = "Zoom in",
                    onTap = {
                        val map = controller.maplibreMap
                        if (map != null) {
                            val targetZoom = (map.cameraPosition.zoom + 1.0).coerceAtMost(22.0)
                            controller.animateCameraWithPadding(CameraUpdateFactory.zoomTo(targetZoom))
                        }
                    },
                )
                action(
                    id = "zoom_out",
                    order = 50,
                    icon = GeoVaultMapFabIcon.Vector(Icons.Default.Remove),
                    contentDescription = "Zoom out",
                    onTap = {
                        val map = controller.maplibreMap
                        if (map != null) {
                            val targetZoom = (map.cameraPosition.zoom - 1.0).coerceAtLeast(1.0)
                            controller.animateCameraWithPadding(CameraUpdateFactory.zoomTo(targetZoom))
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

            val selectedFeature = state.selectedFeature
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                shape = androidx.compose.ui.graphics.RectangleShape,
                backgroundColor = GeoVaultColorTokens.Surface,
                border = BorderStroke(1.dp, GeoVaultColorTokens.BorderLight),
                elevation = 0.dp,
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = viewModel.selectedFeatureLabel(selectedFeature?.properties),
                        color = GeoVaultColorTokens.TextPrimary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

private fun startLocationPanning(
    context: android.content.Context,
    controller: GeoVaultMapController,
    locationPlugin: MapLocationRendererPlugin,
    onSessionReady: (LocationUpdates.LocationUpdatesSession) -> Unit,
) {
    locationPlugin.setEnabled(true)
    locationPlugin.setCameraTracking(false)
    locationPlugin.setAccuracyCircleVisible(true)
    val session = LocationUpdates.startLocationUpdates(
        context = context,
        intervalMs = 2000L,
    ) { latLng, location ->
        if (location != null) {
            locationPlugin.renderLocation(location)
        }
        val map = controller.maplibreMap ?: return@startLocationUpdates
        val currentZoom = map.cameraPosition.zoom
        controller.moveCameraWithPadding(CameraUpdateFactory.newLatLngZoom(latLng, currentZoom))
    }
    onSessionReady(session)
}
