package com.geovault.common.maps.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.GpsNotFixed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.geovault.common.maps.core.GeoVaultBaseMap
import com.geovault.common.maps.core.GeoVaultMapPhase
import com.geovault.common.maps.location.GeoVaultUserLocationCapability
import com.geovault.common.maps.location.LocationUpdates
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.maps.MapLibreMap

data class GeoVaultGpsRecenterController(
    val onRecenter: () -> Unit,
    val fabIcon: GeoVaultMapFabIcon,
    val isLocking: Boolean,
)

fun geoVaultLayerToggleFabAction(
    map: GeoVaultBaseMap,
    id: String = "layers",
    order: Int = 10,
    contentDescription: String = "Toggle map source",
): GeoVaultMapFabAction {
    return GeoVaultMapFabAction(
        id = id,
        order = order,
        icon = GeoVaultMapFabIcon.Vector(Icons.Default.Layers),
        contentDescription = contentDescription,
        onTap = { map.cycleSource() },
    )
}

fun geoVaultZoomInFabAction(
    map: GeoVaultBaseMap,
    id: String = "zoom_in",
    order: Int = 40,
    contentDescription: String = "Zoom in",
): GeoVaultMapFabAction {
    return GeoVaultMapFabAction(
        id = id,
        order = order,
        icon = GeoVaultMapFabIcon.Vector(Icons.Default.Add),
        contentDescription = contentDescription,
        onTap = {
            val mapLibreMap = map.maplibreMap
            if (mapLibreMap != null) {
                map.animateCameraWithPadding(CameraUpdateFactory.zoomBy(1.0))
            }
        },
    )
}

fun geoVaultZoomOutFabAction(
    map: GeoVaultBaseMap,
    id: String = "zoom_out",
    order: Int = 50,
    contentDescription: String = "Zoom out",
): GeoVaultMapFabAction {
    return GeoVaultMapFabAction(
        id = id,
        order = order,
        icon = GeoVaultMapFabIcon.Vector(Icons.Default.Remove),
        contentDescription = contentDescription,
        onTap = {
            val mapLibreMap = map.maplibreMap
            if (mapLibreMap != null) {
                map.animateCameraWithPadding(CameraUpdateFactory.zoomBy(-1.0))
            }
        },
    )
}

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
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
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

private fun Context.hasLocationPermission(): Boolean {
    return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
}

/**
 * Controller state for a toggle-able GPS-follow FAB. The caller wires [onTap] onto a FAB
 * action and observes [isFollowing] to update the icon. Manual pan/zoom cancels the lock —
 * [installCameraGestureWatcher] hooks that up automatically against the host map.
 */
data class GeoVaultGpsFollowController(
    val onTap: () -> Unit,
    val fabIcon: GeoVaultMapFabIcon,
    val isFollowing: Boolean,
)

/**
 * Continuous GPS-follow toggle.
 *  - Tap once -> enable TRACKING camera mode; puck follows every fix.
 *  - Manual pan/zoom -> silently release the lock (camera mode NONE) without altering state.
 *  - Tap again -> explicitly release the lock.
 *
 * Continuous GPS updates are assumed to be driven elsewhere (see
 * [com.geovault.common.maps.location.MapLocationRendererPlugin.startRenderingGpsLocation]).
 *
 * [onPermissionDenied] fires when the user rejects the permission prompt, so the host shell
 * can surface a snackbar.
 */
@Composable
fun rememberGeoVaultGpsFollowController(
    map: GeoVaultBaseMap,
    userLocation: GeoVaultUserLocationCapability,
    onPermissionDenied: (() -> Unit)? = null,
): GeoVaultGpsFollowController {
    val context = LocalContext.current
    // `rememberSaveable` so the "follow my location" lock survives config changes / process
    // death. The restored flag is reapplied via the [DisposableEffect] below that calls
    // `setCameraMode(TRACKING)` whenever `isFollowing` is true.
    var isFollowing by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            engageGpsFollow(map, userLocation)
            isFollowing = true
        } else {
            onPermissionDenied?.invoke()
        }
    }

    // Cancel the lock when the user starts dragging/zooming. Only the `REASON_API_GESTURE`
    // code corresponds to a touch event; programmatic animations (including ours when we
    // engage the lock) don't drop tracking.
    val listener = remember(map) {
        MapLibreMap.OnCameraMoveStartedListener { reason ->
            if (!isFollowing) return@OnCameraMoveStartedListener
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                isFollowing = false
                userLocation.setCameraMode(CameraMode.NONE)
            }
        }
    }
    DisposableEffect(map, listener) {
        map.addOnCameraMoveStartedListener(listener)
        onDispose { map.removeOnCameraMoveStartedListener(listener) }
    }

    // Reapply tracking mode after map rebuilds (style change resets camera mode).
    DisposableEffect(map, isFollowing) {
        if (isFollowing) {
            userLocation.setCameraMode(CameraMode.TRACKING)
        }
        onDispose { }
    }

    val fabIcon = if (isFollowing) {
        GeoVaultMapFabIcon.Vector(Icons.Filled.GpsFixed)
    } else {
        GeoVaultMapFabIcon.Vector(Icons.Outlined.GpsNotFixed)
    }

    val onTap = {
        if (isFollowing) {
            isFollowing = false
            userLocation.setCameraMode(CameraMode.NONE)
        } else if (context.hasLocationPermission()) {
            engageGpsFollow(map, userLocation)
            isFollowing = true
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    return GeoVaultGpsFollowController(
        onTap = onTap,
        fabIcon = fabIcon,
        isFollowing = isFollowing,
    )
}

private fun engageGpsFollow(map: GeoVaultBaseMap, userLocation: GeoVaultUserLocationCapability) {
    map.ensureInteractiveGestures()
    userLocation.setEnabled(true)
    userLocation.setCameraMode(CameraMode.TRACKING)
}

@Composable
fun rememberGeoVaultGpsFollowFabAction(
    map: GeoVaultBaseMap,
    userLocation: GeoVaultUserLocationCapability,
    id: String = "gps_follow",
    order: Int = 30,
    contentDescription: String = "Follow my location",
    onPermissionDenied: (() -> Unit)? = null,
): GeoVaultMapFabAction {
    val controller = rememberGeoVaultGpsFollowController(
        map = map,
        userLocation = userLocation,
        onPermissionDenied = onPermissionDenied,
    )
    return GeoVaultMapFabAction(
        id = id,
        order = order,
        icon = controller.fabIcon,
        contentDescription = contentDescription,
        onTap = controller.onTap,
    )
}

/**
 * Controller state for a toggle-able camera orientation (compass) lock. Pairs with
 * [rememberGeoVaultOrientationLockController] / [rememberGeoVaultOrientationLockFabAction].
 */
data class GeoVaultOrientationLockController(
    val onTap: () -> Unit,
    val fabIcon: GeoVaultMapFabIcon,
    val isLocked: Boolean,
)

/**
 * Toggle that rotates the camera to match device heading using MapLibre
 * `CameraMode.TRACKING_COMPASS`. Manual pan/zoom releases the lock. The caller is expected
 * to feed bearings into the puck (via `HeadingSensor`) separately so the directional arrow
 * rotates as well.
 */
@Composable
fun rememberGeoVaultOrientationLockController(
    map: GeoVaultBaseMap,
    userLocation: GeoVaultUserLocationCapability,
    onPermissionDenied: (() -> Unit)? = null,
): GeoVaultOrientationLockController {
    val context = LocalContext.current
    // Persist across config changes / process death so the compass lock survives a rotation.
    // The DisposableEffect below reapplies `TRACKING_COMPASS` on restore, so visual state
    // follows the saved flag without extra glue.
    var isLocked by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            engageOrientationLock(map, userLocation)
            isLocked = true
        } else {
            onPermissionDenied?.invoke()
        }
    }

    val listener = remember(map) {
        MapLibreMap.OnCameraMoveStartedListener { reason ->
            if (!isLocked) return@OnCameraMoveStartedListener
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                isLocked = false
                userLocation.setCameraMode(CameraMode.NONE)
            }
        }
    }
    DisposableEffect(map, listener) {
        map.addOnCameraMoveStartedListener(listener)
        onDispose { map.removeOnCameraMoveStartedListener(listener) }
    }
    DisposableEffect(map, isLocked) {
        if (isLocked) userLocation.setCameraMode(CameraMode.TRACKING_COMPASS)
        onDispose { }
    }

    val fabIcon = if (isLocked) {
        GeoVaultMapFabIcon.Vector(Icons.Filled.Explore)
    } else {
        GeoVaultMapFabIcon.Vector(Icons.Outlined.Explore)
    }

    val onTap = {
        if (isLocked) {
            isLocked = false
            userLocation.setCameraMode(CameraMode.NONE)
        } else if (context.hasLocationPermission()) {
            engageOrientationLock(map, userLocation)
            isLocked = true
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    return GeoVaultOrientationLockController(
        onTap = onTap,
        fabIcon = fabIcon,
        isLocked = isLocked,
    )
}

private fun engageOrientationLock(map: GeoVaultBaseMap, userLocation: GeoVaultUserLocationCapability) {
    map.ensureInteractiveGestures()
    userLocation.setEnabled(true)
    userLocation.setCameraMode(CameraMode.TRACKING_COMPASS)
}

@Composable
fun rememberGeoVaultOrientationLockFabAction(
    map: GeoVaultBaseMap,
    userLocation: GeoVaultUserLocationCapability,
    id: String = "orientation_lock",
    order: Int = 35,
    contentDescription: String = "Follow my heading",
    onPermissionDenied: (() -> Unit)? = null,
): GeoVaultMapFabAction {
    val controller = rememberGeoVaultOrientationLockController(
        map = map,
        userLocation = userLocation,
        onPermissionDenied = onPermissionDenied,
    )
    return GeoVaultMapFabAction(
        id = id,
        order = order,
        icon = controller.fabIcon,
        contentDescription = contentDescription,
        onTap = controller.onTap,
    )
}
