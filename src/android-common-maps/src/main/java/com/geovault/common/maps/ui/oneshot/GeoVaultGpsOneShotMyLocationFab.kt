package com.geovault.common.maps.ui.oneshot

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
import com.geovault.common.maps.core.geoVaultRetargetCameraPositionWithMinimumZoom
import com.geovault.common.maps.location.GeoVaultMapLocationPermission
import com.geovault.common.maps.location.GeoVaultUserLocationCapability
import com.geovault.common.maps.location.LocationUpdates
import com.geovault.common.maps.location.geoVaultMapHasFineOrCoarseLocation
import com.geovault.common.maps.ui.GeoVaultMapFabAction
import com.geovault.common.maps.ui.GeoVaultMapFabIcon
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng

/** Minimum zoom used when one-shot GPS starts from a broad map; never zoom out to this value. */
private const val GPS_ONE_SHOT_MIN_ZOOM: Double = 12.0

/**
 * Controller for a single map jump to the device location (spinner, icon). Does **not** enable
 * continuous MapLibre camera tracking — use
 * [com.geovault.common.maps.ui.camerafollow.rememberGeoVaultMapHeadingFollowFabBundle] for that.
 */
data class GeoVaultGpsOneShotMyLocationController(
    val onJumpToMyLocation: () -> Unit,
    val fabIcon: GeoVaultMapFabIcon,
    val isWaitingForFix: Boolean,
)

@Composable
fun rememberGeoVaultGpsOneShotMyLocationController(
    map: GeoVaultBaseMap,
    userLocation: GeoVaultUserLocationCapability,
    onLocationResolved: ((LatLng) -> Unit)? = null,
    onPermissionDenied: (() -> Unit)? = null,
    /**
     * When false, only resolves coordinates and does not show the MapLibre user location puck,
     * accuracy ring, or camera move (for hosts that handle the marker and camera themselves).
     */
    showUserLocationPuck: Boolean = true,
    /**
     * When true, the FAB shows the engaged (fixed) icon and one-shot lookup skips the spinner,
     * because the host already has continuous position follow from the heading-follow bundle.
     */
    positionFollowActive: Boolean = false,
    /**
     * Resolved at tap time. When non-null, the controller uses the supplied coordinate as the
     * recenter target and skips the GPS one-shot lookup AND the MapLibre user-location puck.
     * Hosts use this to recenter on their own authoritative position (e.g. an active recording
     * tracker's last fix) without painting a duplicate puck on top of their marker.
     */
    coordinateOverride: (() -> LatLng?)? = null,
): GeoVaultGpsOneShotMyLocationController {
    val context = LocalContext.current
    var hadSuccessfulJump by remember { mutableStateOf(false) }
    var waitingForFix by remember { mutableStateOf(false) }
    var activeRequestId by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        onDispose {
            activeRequestId += 1
        }
    }

    fun animateCameraToTarget(target: LatLng, requestId: Int) {
        val mapLibreMap = map.maplibreMap ?: return
        map.animateCameraWithPadding(
            CameraUpdateFactory.newCameraPosition(
                geoVaultRetargetCameraPositionWithMinimumZoom(
                    current = mapLibreMap.cameraPosition,
                    target = target,
                    minimumZoom = GPS_ONE_SHOT_MIN_ZOOM,
                ),
            ),
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
                if (requestId == activeRequestId) {
                    map.ensureInteractiveGestures()
                }
            },
            350L,
        )
    }

    fun jumpToMyLocation(showSpinner: Boolean) {
        if (showSpinner) {
            waitingForFix = true
        }
        activeRequestId += 1
        val requestId = activeRequestId
        map.ensureInteractiveGestures()

        // OVERRIDE PATH: host has its own authoritative coordinate (e.g. the user's tracker
        // marker while actively recording). Skip the GPS lookup and the MapLibre puck so we
        // don't paint a duplicate chevron on top of the host's marker.
        val overrideTarget = coordinateOverride?.invoke()
        if (overrideTarget != null) {
            if (showSpinner) {
                waitingForFix = false
            }
            if (map.phase.value != GeoVaultMapPhase.Ready) return
            hadSuccessfulJump = true
            onLocationResolved?.invoke(overrideTarget)
            animateCameraToTarget(overrideTarget, requestId)
            return
        }

        if (showUserLocationPuck) {
            userLocation.setEnabled(true)
            userLocation.setCameraTracking(false)
            userLocation.setAccuracyCircleVisible(true)
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (requestId != activeRequestId) return@post
            LocationUpdates.getFreshCurrentLocation(context) { latLng ->
                if (requestId != activeRequestId) return@getFreshCurrentLocation
                if (showSpinner) {
                    waitingForFix = false
                }
                if (latLng == null) {
                    if (!showUserLocationPuck) {
                        userLocation.setEnabled(false)
                        userLocation.setAccuracyCircleVisible(false)
                    }
                    return@getFreshCurrentLocation
                }
                if (map.phase.value != GeoVaultMapPhase.Ready) return@getFreshCurrentLocation
                hadSuccessfulJump = true
                onLocationResolved?.invoke(latLng)
                if (showUserLocationPuck) {
                    val syntheticLocation = android.location.Location("geovault-gps-oneshot").apply {
                        latitude = latLng.latitude
                        longitude = latLng.longitude
                        accuracy = 10f
                        time = System.currentTimeMillis()
                    }
                    userLocation.renderLocation(syntheticLocation)
                    val mapLibreMap = map.maplibreMap ?: return@getFreshCurrentLocation
                    map.animateCameraWithPadding(
                        CameraUpdateFactory.newCameraPosition(
                            geoVaultRetargetCameraPositionWithMinimumZoom(
                                current = mapLibreMap.cameraPosition,
                                target = latLng,
                                minimumZoom = GPS_ONE_SHOT_MIN_ZOOM,
                            ),
                        ),
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
                            if (requestId == activeRequestId) {
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
            jumpToMyLocation(showSpinner = !(hadSuccessfulJump || positionFollowActive))
        } else {
            waitingForFix = false
            onPermissionDenied?.invoke()
        }
    }

    val showsEngagedIcon = hadSuccessfulJump || positionFollowActive
    val fabIcon = when {
        waitingForFix -> GeoVaultMapFabIcon.Spinner(spinnerColor = Color.White)
        showsEngagedIcon -> GeoVaultMapFabIcon.Vector(Icons.Filled.GpsFixed)
        else -> GeoVaultMapFabIcon.Vector(Icons.Outlined.GpsNotFixed)
    }

    val onJump: () -> Unit = {
        if (!waitingForFix) {
            if (context.geoVaultMapHasFineOrCoarseLocation()) {
                jumpToMyLocation(showSpinner = !showsEngagedIcon)
            } else {
                permissionLauncher.launch(GeoVaultMapLocationPermission.FINE_AND_COARSE)
            }
        }
    }

    return GeoVaultGpsOneShotMyLocationController(
        onJumpToMyLocation = onJump,
        fabIcon = fabIcon,
        isWaitingForFix = waitingForFix,
    )
}

@Composable
fun rememberGeoVaultGpsOneShotMyLocationFabAction(
    map: GeoVaultBaseMap,
    userLocation: GeoVaultUserLocationCapability,
    id: String = "gps_one_shot_my_location",
    order: Int = 30,
    contentDescription: String = "Recenter on my location",
    onLocationResolved: ((LatLng) -> Unit)? = null,
    onPermissionDenied: (() -> Unit)? = null,
    showUserLocationPuck: Boolean = true,
    positionFollowActive: Boolean = false,
    coordinateOverride: (() -> LatLng?)? = null,
): GeoVaultMapFabAction {
    val controller = rememberGeoVaultGpsOneShotMyLocationController(
        map = map,
        userLocation = userLocation,
        onLocationResolved = onLocationResolved,
        onPermissionDenied = onPermissionDenied,
        showUserLocationPuck = showUserLocationPuck,
        positionFollowActive = positionFollowActive,
        coordinateOverride = coordinateOverride,
    )
    return GeoVaultMapFabAction(
        id = id,
        order = order,
        icon = controller.fabIcon,
        contentDescription = contentDescription,
        onTap = controller.onJumpToMyLocation,
    )
}
