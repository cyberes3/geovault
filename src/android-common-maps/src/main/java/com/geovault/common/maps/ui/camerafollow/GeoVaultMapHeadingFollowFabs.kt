package com.geovault.common.maps.ui.camerafollow

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.GpsNotFixed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import com.geovault.common.maps.core.GeoVaultBaseMap
import com.geovault.common.maps.core.geoVaultResetCameraBearingAndTilt
import com.geovault.common.maps.core.latLngOrNull
import com.geovault.common.maps.location.GeoVaultMapLocationPermission
import com.geovault.common.maps.location.GeoVaultUserLocationCapability
import com.geovault.common.maps.location.MapLocationRendererPlugin
import com.geovault.common.maps.location.geoVaultMapHasFineOrCoarseLocation
import com.geovault.common.maps.ui.GeoVaultMapCameraFollowMachine
import com.geovault.common.maps.ui.GeoVaultMapCameraFollowState
import com.geovault.common.maps.R
import com.geovault.common.maps.ui.GeoVaultMapFabAction
import com.geovault.common.maps.ui.GeoVaultMapFabIcon
import com.geovault.common.maps.ui.camera.GeoVaultMapCameraInteractionEffect
import org.maplibre.android.location.modes.CameraMode

/**
 * Heading/compass follow FAB, GPS position-follow FAB, and MapLibre location camera wiring.
 *
 * One-shot “animate to my location once” (e.g. tracker escape hatch) is
 * [com.geovault.common.maps.ui.oneshot.rememberGeoVaultGpsOneShotMyLocationFabAction].
 */
data class GeoVaultMapHeadingFollowFabBundle(
    val headingFollowFab: GeoVaultMapFabAction,
    /**
     * Toggles continuous position follow (camera follows fixes / recenter without MapLibre
     * [CameraMode.TRACKING]). Icon reflects [positionFollowDesired].
     */
    val gpsPositionFollowFab: GeoVaultMapFabAction,
    /**
     * Clears follow, then runs [block]. The only supported way to move the camera from a host
     * while this bundle exists (Home, launch framing, fit bounds).
     */
    val runProgrammaticCamera: (() -> Unit) -> Unit,
    /**
     * True while position / camera follow is active (manual camera follow in the heading-follow
     * bundle; no MapLibre [CameraMode.TRACKING]).
     */
    val positionFollowDesired: Boolean,
    /** True while the heading / compass follow FAB is engaged. */
    val headingFollowDesired: Boolean,
)

private enum class GeoVaultMapHeadingFollowPendingGrant {
    Heading,
    GpsPositionFollow,
}

/** If no bearing arrives (e.g. missing rotation sensor), stop showing the heading FAB spinner. */
private const val HEADING_FAB_BEARING_WAIT_TIMEOUT_MS: Long = 2_500L

/** Minimum zoom used when GPS follow starts from a broad map; never zoom out to this value. */
private const val GPS_FOLLOW_MIN_ZOOM: Double = 10.0

/**
 * Coordinates the **heading** follow FAB, **GPS position-follow** FAB, and MapLibre [CameraMode].
 *
 * For a one-shot recenter (no continuous follow), use
 * [com.geovault.common.maps.ui.oneshot.rememberGeoVaultGpsOneShotMyLocationFabAction].
 *
 * - A user pan/zoom clears **position** and **heading** follow so camera updates do not fight the gesture.
 * - Map bearing is driven manually only when **both** position and heading follow are on.
 * - [allowFollowCamera] lets the host suppress camera tracking (e.g. map not ready) while
 *   keeping FAB toggle state.
 */
@Composable
fun rememberGeoVaultMapHeadingFollowFabBundle(
    map: GeoVaultBaseMap,
    userLocation: GeoVaultUserLocationCapability,
    allowFollowCamera: Boolean = true,
    gpsPositionFollowFabId: String = "gps_position_follow",
    gpsPositionFollowFabOrder: Int = 30,
    gpsPositionFollowContentDescription: String = "Show your live GPS location on the map.",
    orientationFollowFabId: String = "orientation_lock",
    orientationFollowFabOrder: Int = 35,
    orientationFollowContentDescription: String = "Rotate the map to match the direction you are facing.",
    onPermissionDenied: (() -> Unit)? = null,
): GeoVaultMapHeadingFollowFabBundle {
    val context = LocalContext.current
    var positionFollowDesired by rememberSaveable { mutableStateOf(false) }
    var headingFollowDesired by rememberSaveable { mutableStateOf(false) }
    var waitingForInitialLock by rememberSaveable { mutableStateOf(false) }
    fun followState(): GeoVaultMapCameraFollowState =
        GeoVaultMapCameraFollowState(positionFollowDesired, headingFollowDesired)
    val lastLogicalRef = remember {
        object {
            var last = GeoVaultMapCameraFollowState.NONE
        }
    }
    var pendingGrant by remember { mutableStateOf<GeoVaultMapHeadingFollowPendingGrant?>(null) }
    val plugin = userLocation as? MapLocationRendererPlugin
    val followController = remember(map) {
        GeoVaultMapCameraFollowController(
            camera = GeoVaultBaseMapFollowCamera(map),
            minimumRecenterZoom = GPS_FOLLOW_MIN_ZOOM,
        )
    }
    fun snapToCurrentPuckLocation(positionDesired: Boolean, headingDesired: Boolean): Boolean {
        val location = plugin?.getLastLocation()
        if (!positionDesired || !allowFollowCamera || location == null) return false
        followController.updateFollowState(
            positionFollowDesired = positionDesired,
            headingFollowDesired = headingDesired,
            allowFollowCamera = allowFollowCamera,
        )
        val target = latLngOrNull(location.latitude, location.longitude)
        if (target != null) {
            followController.recenter(target)
            return true
        }
        return false
    }
    var headingFabBearingDeg by remember { mutableFloatStateOf(0f) }
    /** True after first smoothed bearing while heading follow is on (compass may rotate). */
    var headingFabBearingReady by remember { mutableStateOf(false) }

    LaunchedEffect(headingFollowDesired) {
        if (!headingFollowDesired) {
            headingFabBearingReady = false
            return@LaunchedEffect
        }
        headingFabBearingReady = false
        delay(HEADING_FAB_BEARING_WAIT_TIMEOUT_MS)
        if (headingFollowDesired) {
            headingFabBearingReady = true
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result[GeoVaultMapLocationPermission.FINE_AND_COARSE[0]] == true ||
            result[GeoVaultMapLocationPermission.FINE_AND_COARSE[1]] == true
        val pending = pendingGrant
        pendingGrant = null
        if (granted) {
            when (pending) {
                GeoVaultMapHeadingFollowPendingGrant.Heading -> {
                    val next = GeoVaultMapCameraFollowMachine.toggleHeadingOnTap(followState())
                    positionFollowDesired = next.positionFollowDesired
                    headingFollowDesired = next.headingFollowDesired
                }
                GeoVaultMapHeadingFollowPendingGrant.GpsPositionFollow -> {
                    val next = GeoVaultMapCameraFollowMachine.enablePositionFollowOnGpsTap(followState())
                    positionFollowDesired = next.positionFollowDesired
                    headingFollowDesired = next.headingFollowDesired
                    waitingForInitialLock = next.positionFollowDesired &&
                        !snapToCurrentPuckLocation(next.positionFollowDesired, next.headingFollowDesired)
                }
                null -> Unit
            }
        } else {
            waitingForInitialLock = false
            onPermissionDenied?.invoke()
        }
    }

    val latestFollowState = rememberUpdatedState(
        GeoVaultMapCameraFollowState(positionFollowDesired, headingFollowDesired),
    )
    GeoVaultMapCameraInteractionEffect(
        map = map,
        onCameraTakeover = {
            val prev = latestFollowState.value
            if (!prev.positionFollowDesired && !prev.headingFollowDesired) return@GeoVaultMapCameraInteractionEffect
            val next = GeoVaultMapCameraFollowMachine.afterUserGesture(prev)
            if (next != prev) {
                positionFollowDesired = next.positionFollowDesired
                headingFollowDesired = next.headingFollowDesired
                waitingForInitialLock = false
            }
        },
        onUserOwnedZoom = {},
    )

    DisposableEffect(
        map,
        userLocation,
        allowFollowCamera,
        positionFollowDesired,
        headingFollowDesired,
    ) {
        val nextLogical = GeoVaultMapCameraFollowState(positionFollowDesired, headingFollowDesired)
        if (GeoVaultMapCameraFollowMachine.shouldResetNorthToUp(lastLogicalRef.last, nextLogical)) {
            geoVaultResetCameraBearingAndTilt(map)
        }
        userLocation.setCameraMode(CameraMode.NONE)
        followController.updateFollowState(
            positionFollowDesired = positionFollowDesired,
            headingFollowDesired = headingFollowDesired,
            allowFollowCamera = allowFollowCamera,
        )
        lastLogicalRef.last = nextLogical
        onDispose { }
    }

    DisposableEffect(plugin, headingFollowDesired) {
        if (plugin == null || !headingFollowDesired) {
            return@DisposableEffect onDispose { }
        }
        val listener: (Float) -> Unit = {
            headingFabBearingDeg = it
            headingFabBearingReady = true
            followController.onBearing(it)
        }
        plugin.addBearingListener(listener)
        onDispose { plugin.removeBearingListener(listener) }
    }

    DisposableEffect(map, plugin, positionFollowDesired, headingFollowDesired, allowFollowCamera) {
        val shouldObserveLocations = positionFollowDesired && allowFollowCamera
        if (!shouldObserveLocations || plugin == null) {
            return@DisposableEffect onDispose { }
        }
        val listener: (android.location.Location) -> Unit = listener@{ loc ->
            val latLng = latLngOrNull(loc.latitude, loc.longitude) ?: return@listener
            waitingForInitialLock = false
            followController.onLocationFix(latLng)
        }
        plugin.addLocationListener(listener)
        onDispose { plugin.removeLocationListener(listener) }
    }

    val runProgrammaticCamera: (() -> Unit) -> Unit = { block ->
        val next = GeoVaultMapCameraFollowMachine.afterProgrammaticCamera(followState())
        positionFollowDesired = next.positionFollowDesired
        headingFollowDesired = next.headingFollowDesired
        waitingForInitialLock = false
        followController.clearFollow()
        block()
    }

    val onHeadingTap: () -> Unit = {
        if (context.geoVaultMapHasFineOrCoarseLocation()) {
            val next = GeoVaultMapCameraFollowMachine.toggleHeadingOnTap(followState())
            positionFollowDesired = next.positionFollowDesired
            headingFollowDesired = next.headingFollowDesired
        } else {
            pendingGrant = GeoVaultMapHeadingFollowPendingGrant.Heading
            permissionLauncher.launch(GeoVaultMapLocationPermission.FINE_AND_COARSE)
        }
    }

    val onGpsPositionFollowTap: () -> Unit = {
        if (context.geoVaultMapHasFineOrCoarseLocation()) {
            val next = GeoVaultMapCameraFollowMachine.enablePositionFollowOnGpsTap(followState())
            positionFollowDesired = next.positionFollowDesired
            headingFollowDesired = next.headingFollowDesired
            waitingForInitialLock = next.positionFollowDesired &&
                !snapToCurrentPuckLocation(next.positionFollowDesired, next.headingFollowDesired)
        } else {
            waitingForInitialLock = false
            pendingGrant = GeoVaultMapHeadingFollowPendingGrant.GpsPositionFollow
            permissionLauncher.launch(GeoVaultMapLocationPermission.FINE_AND_COARSE)
        }
    }

    val gpsPositionFab = GeoVaultMapFabAction(
        id = gpsPositionFollowFabId,
        order = gpsPositionFollowFabOrder,
        icon = when {
            waitingForInitialLock -> GeoVaultMapFabIcon.Spinner()
            positionFollowDesired -> GeoVaultMapFabIcon.Vector(Icons.Filled.GpsFixed)
            else -> GeoVaultMapFabIcon.Vector(Icons.Outlined.GpsNotFixed)
        },
        contentDescription = gpsPositionFollowContentDescription,
        tooltip = gpsPositionFollowContentDescription,
        onTap = onGpsPositionFollowTap,
    )

    val showHeadingCompass =
        headingFollowDesired && headingFabBearingReady
    val orientationFab = GeoVaultMapFabAction(
        id = orientationFollowFabId,
        order = orientationFollowFabOrder,
        icon = when {
            !headingFollowDesired -> GeoVaultMapFabIcon.Vector(Icons.Outlined.Explore)
            showHeadingCompass -> GeoVaultMapFabIcon.Drawable(R.drawable.gv_common_fab_heading_compass)
            else -> GeoVaultMapFabIcon.Spinner()
        },
        contentDescription = orientationFollowContentDescription,
        tooltip = orientationFollowContentDescription,
        onTap = onHeadingTap,
        iconRotationDegrees = if (showHeadingCompass) -headingFabBearingDeg else 0f,
        useIntrinsicIconColors = showHeadingCompass,
    )

    return GeoVaultMapHeadingFollowFabBundle(
        headingFollowFab = orientationFab,
        gpsPositionFollowFab = gpsPositionFab,
        runProgrammaticCamera = runProgrammaticCamera,
        positionFollowDesired = positionFollowDesired,
        headingFollowDesired = headingFollowDesired,
    )
}
