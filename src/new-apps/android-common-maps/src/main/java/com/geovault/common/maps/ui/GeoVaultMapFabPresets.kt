package com.geovault.common.maps.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.GpsNotFixed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.geovault.common.maps.core.GeoVaultMapController
import com.geovault.common.maps.core.MapLibreManager
import com.geovault.common.maps.location.LocationUpdates
import com.geovault.common.maps.location.MapLocationRendererPlugin
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng

fun geoVaultLayerToggleFabAction(
    controller: GeoVaultMapController,
    id: String = "layers",
    order: Int = 10,
    contentDescription: String = "Toggle map source",
): GeoVaultMapFabAction {
    return GeoVaultMapFabAction(
        id = id,
        order = order,
        icon = GeoVaultMapFabIcon.Vector(Icons.Default.Layers),
        contentDescription = contentDescription,
        onTap = { controller.cycleSource() },
    )
}

fun geoVaultZoomInFabAction(
    controller: GeoVaultMapController,
    id: String = "zoom_in",
    order: Int = 40,
    contentDescription: String = "Zoom in",
    maxZoom: Double = MapLibreManager.MAX_ZOOM_LEVEL.toDouble(),
): GeoVaultMapFabAction {
    return GeoVaultMapFabAction(
        id = id,
        order = order,
        icon = GeoVaultMapFabIcon.Vector(Icons.Default.Add),
        contentDescription = contentDescription,
        onTap = {
            val map = controller.maplibreMap
            if (map != null) {
                val targetZoom = (map.cameraPosition.zoom + 1.0).coerceAtMost(maxZoom)
                controller.animateCameraWithPadding(CameraUpdateFactory.zoomTo(targetZoom))
            }
        },
    )
}

fun geoVaultZoomOutFabAction(
    controller: GeoVaultMapController,
    id: String = "zoom_out",
    order: Int = 50,
    contentDescription: String = "Zoom out",
    minZoom: Double = 1.0,
): GeoVaultMapFabAction {
    return GeoVaultMapFabAction(
        id = id,
        order = order,
        icon = GeoVaultMapFabIcon.Vector(Icons.Default.Remove),
        contentDescription = contentDescription,
        onTap = {
            val map = controller.maplibreMap
            if (map != null) {
                val targetZoom = (map.cameraPosition.zoom - 1.0).coerceAtLeast(minZoom)
                controller.animateCameraWithPadding(CameraUpdateFactory.zoomTo(targetZoom))
            }
        },
    )
}

@Composable
fun rememberGeoVaultGpsRecenterFabAction(
    controller: GeoVaultMapController,
    locationPlugin: MapLocationRendererPlugin,
    id: String = "gps_recenter",
    order: Int = 30,
    contentDescription: String = "Recenter on my location",
    onLocationResolved: ((LatLng) -> Unit)? = null,
): GeoVaultMapFabAction {
    val context = LocalContext.current
    var locationEnabled by remember { mutableStateOf(false) }
    var locationLocking by remember { mutableStateOf(false) }

    fun recenterOnUser(showSpinner: Boolean) {
        if (showSpinner) {
            locationLocking = true
        }
        controller.ensureInteractiveGestures()
        locationPlugin.setEnabled(true)
        locationPlugin.setCameraTracking(false)
        locationPlugin.setAccuracyCircleVisible(true)
        // Post to next UI tick so loading state is visible before fast callbacks.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            LocationUpdates.getCurrentLocation(context) { latLng ->
                if (showSpinner) {
                    locationLocking = false
                }
                if (latLng == null) return@getCurrentLocation
                locationEnabled = true
                onLocationResolved?.invoke(latLng)
                val syntheticLocation = android.location.Location("geovault-gps-fab").apply {
                    latitude = latLng.latitude
                    longitude = latLng.longitude
                    accuracy = 10f
                    time = System.currentTimeMillis()
                }
                locationPlugin.renderLocation(syntheticLocation)
                val map = controller.maplibreMap ?: return@getCurrentLocation
                val currentZoom = map.cameraPosition.zoom.coerceAtLeast(1.0)
                controller.animateCameraWithPadding(
                    CameraUpdateFactory.newLatLngZoom(latLng, currentZoom),
                    callback = object : org.maplibre.android.maps.MapLibreMap.CancelableCallback {
                        override fun onCancel() {
                            controller.ensureInteractiveGestures()
                        }

                        override fun onFinish() {
                            controller.ensureInteractiveGestures()
                        }
                    },
                )
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    { controller.ensureInteractiveGestures() },
                    350L,
                )
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            recenterOnUser(showSpinner = !locationEnabled)
        } else {
            locationLocking = false
        }
    }

    val icon = when {
        locationLocking -> GeoVaultMapFabIcon.Spinner(spinnerColor = Color.White)
        locationEnabled -> GeoVaultMapFabIcon.Vector(Icons.Filled.GpsFixed)
        else -> GeoVaultMapFabIcon.Vector(Icons.Outlined.GpsNotFixed)
    }

    return GeoVaultMapFabAction(
        id = id,
        order = order,
        icon = icon,
        contentDescription = contentDescription,
        onTap = {
            if (!locationLocking) {
                if (context.hasLocationPermission()) {
                    recenterOnUser(showSpinner = !locationEnabled)
                } else {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                }
            }
        },
    )
}

private fun Context.hasLocationPermission(): Boolean {
    return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
