package com.geovault.common.maps.ui.recenter

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.outlined.GpsNotFixed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.geovault.common.maps.core.GeoVaultBaseMap
import com.geovault.common.maps.core.GeoVaultMapPhase
import com.geovault.common.maps.location.GeoVaultMapLocationPermission
import com.geovault.common.maps.location.GeoVaultUserLocationCapability
import com.geovault.common.maps.location.LocationUpdates
import com.geovault.common.maps.location.geoVaultMapHasFineOrCoarseLocation
import com.geovault.common.maps.ui.GeoVaultMapFabAction
import com.geovault.common.maps.ui.GeoVaultMapFabIcon
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng

/**
 * One-shot "recenter map on my location" FAB preset.
 *
 * For continuous GPS + heading follow (MapLibre [CameraMode]), use
 * [com.geovault.common.maps.ui.camerafollow.rememberGeoVaultMapCameraFollowFabBundle] and
 * [com.geovault.common.maps.ui.GeoVaultMapCameraFollowMachine].
 */
data class GeoVaultGpsRecenterController(
    val onRecenter: () -> Unit,
    val fabIcon: GeoVaultMapFabIcon,
    val isLocking: Boolean,
)

@Composable
fun rememberGeoVaultGpsRecenterController(
    map: GeoVaultBaseMap,
    userLocation: GeoVaultUserLocationCapability,
    onLocationResolved: ((LatLng) -> Unit)? = null,
    /** When false, only resolves coordinates and does not show the MapLibre user location puck, accuracy ring, or camera move (for hosts that handle the marker and camera themselves). */
    showUserLocationPuck: Boolean = true,
): GeoVaultGpsRecenterController {
    val context = LocalContext.current
    var locationEnabled by remember { mutableStateOf(false) }
    var locationLocking by remember { mutableStateOf(false) }
    var activeRecenterRequestId by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        onDispose {
            // Invalidate in-flight callbacks after host leaves composition.
            activeRecenterRequestId += 1
        }
    }

    fun recenterOnUser(showSpinner: Boolean) {
        if (showSpinner) {
            locationLocking = true
        }
        activeRecenterRequestId += 1
        val requestId = activeRecenterRequestId
        map.ensureInteractiveGestures()
        if (showUserLocationPuck) {
            userLocation.setEnabled(true)
            userLocation.setCameraTracking(false)
            userLocation.setAccuracyCircleVisible(true)
        }
        // Post to next UI tick so loading state is visible before fast callbacks.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (requestId != activeRecenterRequestId) return@post
            LocationUpdates.getCurrentLocation(context) { latLng ->
                if (requestId != activeRecenterRequestId) return@getCurrentLocation
                if (showSpinner) {
                    locationLocking = false
                }
                if (latLng == null) {
                    if (!showUserLocationPuck) {
                        userLocation.setEnabled(false)
                        userLocation.setAccuracyCircleVisible(false)
                    }
                    return@getCurrentLocation
                }
                if (map.phase.value != GeoVaultMapPhase.Ready) return@getCurrentLocation
                locationEnabled = true
                onLocationResolved?.invoke(latLng)
                if (showUserLocationPuck) {
                    val syntheticLocation = android.location.Location("geovault-gps-fab").apply {
                        latitude = latLng.latitude
                        longitude = latLng.longitude
                        accuracy = 10f
                        time = System.currentTimeMillis()
                    }
                    userLocation.renderLocation(syntheticLocation)
                    val mapLibreMap = map.maplibreMap ?: return@getCurrentLocation
                    val currentZoom = mapLibreMap.cameraPosition.zoom.coerceAtLeast(1.0)
                    map.animateCameraWithPadding(
                        CameraUpdateFactory.newLatLngZoom(latLng, currentZoom),
                        callback = object : org.maplibre.android.maps.MapLibreMap.CancelableCallback {
                            override fun onCancel() {
                                map.ensureInteractiveGestures()
                            }

                            override fun onFinish() {
                                map.ensureInteractiveGestures()
                            }
                        },
                    )
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        {
                            if (requestId == activeRecenterRequestId) {
                                map.ensureInteractiveGestures()
                            }
                        },
                        350L,
                    )
                } else {
                    userLocation.setEnabled(false)
                    userLocation.setAccuracyCircleVisible(false)
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result[GeoVaultMapLocationPermission.FINE_AND_COARSE[0]] == true ||
            result[GeoVaultMapLocationPermission.FINE_AND_COARSE[1]] == true
        if (granted) {
            recenterOnUser(showSpinner = !locationEnabled)
        } else {
            locationLocking = false
        }
    }

    val fabIcon = when {
        locationLocking -> GeoVaultMapFabIcon.Spinner(spinnerColor = Color.White)
        locationEnabled -> GeoVaultMapFabIcon.Vector(Icons.Filled.GpsFixed)
        else -> GeoVaultMapFabIcon.Vector(Icons.Outlined.GpsNotFixed)
    }

    val onRecenter: () -> Unit = {
        if (!locationLocking) {
            if (context.geoVaultMapHasFineOrCoarseLocation()) {
                recenterOnUser(showSpinner = !locationEnabled)
            } else {
                permissionLauncher.launch(GeoVaultMapLocationPermission.FINE_AND_COARSE)
            }
        }
    }

    return GeoVaultGpsRecenterController(
        onRecenter = onRecenter,
        fabIcon = fabIcon,
        isLocking = locationLocking,
    )
}

@Composable
fun rememberGeoVaultGpsRecenterFabAction(
    map: GeoVaultBaseMap,
    userLocation: GeoVaultUserLocationCapability,
    id: String = "gps_recenter",
    order: Int = 30,
    contentDescription: String = "Recenter on my location",
    onLocationResolved: ((LatLng) -> Unit)? = null,
    showUserLocationPuck: Boolean = true,
): GeoVaultMapFabAction {
    val controller = rememberGeoVaultGpsRecenterController(
        map = map,
        userLocation = userLocation,
        onLocationResolved = onLocationResolved,
        showUserLocationPuck = showUserLocationPuck,
    )
    return GeoVaultMapFabAction(
        id = id,
        order = order,
        icon = controller.fabIcon,
        contentDescription = contentDescription,
        onTap = controller.onRecenter,
    )
}
