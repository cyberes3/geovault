package com.geovault.common.maps.ui.camerafollow

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.geovault.common.maps.core.GeoVaultBaseMap
import com.geovault.common.maps.core.geoVaultResetCameraBearingAndTilt
import com.geovault.common.maps.location.GeoVaultMapLocationPermission
import com.geovault.common.maps.location.GeoVaultUserLocationCapability
import com.geovault.common.maps.location.LocationUpdates
import com.geovault.common.maps.location.MapLocationRendererPlugin
import com.geovault.common.maps.location.geoVaultMapHasFineOrCoarseLocation
import com.geovault.common.maps.ui.GeoVaultMapCameraFollowMachine
import com.geovault.common.maps.ui.GeoVaultMapCameraFollowState
import com.geovault.common.maps.ui.GeoVaultMapFabAction
import com.geovault.common.maps.ui.GeoVaultMapFabIcon
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.maps.MapLibreMap

/**
 * Heading/compass follow FAB and MapLibre location camera wiring. **One-shot** "go to my
 * position" is [com.geovault.common.maps.ui.recenter.rememberGeoVaultGpsRecenterFabAction], not
 * a second FAB here.
 */
data class GeoVaultMapHeadingFollowFabBundle(
    val headingFollowFab: GeoVaultMapFabAction,
    /**
     * Clears follow flags and camera tracking before host-driven camera moves (fit bounds,
     * selection zoom, navigation framing) so MapLibre does not fight programmatic animations.
     */
    val clearForProgrammaticCameraMove: () -> Unit,
    /**
     * True while position / camera follow is active ([CameraMode.TRACKING] or the dual-mode
     * manual path when heading is also on).
     */
    val positionFollowDesired: Boolean,
    /** True while the heading / compass follow FAB is engaged. */
    val headingFollowDesired: Boolean,
)

private enum class GeoVaultMapHeadingFollowPendingGrant {
    Heading,
}

/**
 * Zoom the first time **position** follow is engaged in a session (typically via the heading
 * FAB), so the user is not at country scale. `rememberSaveable` keeps the flag across config
 * changes.
 */
private const val INITIAL_FOLLOW_ZOOM: Double = 10.0

/**
 * Coordinates the **heading** follow FAB, MapLibre [CameraMode], and optional first-follow zoom.
 *
 * For a one-shot recenter, use
 * [com.geovault.common.maps.ui.recenter.rememberGeoVaultGpsRecenterFabAction].
 *
 * - A user pan/zoom clears **position** follow only; heading follow can stay (compass lock).
 * - Map bearing is driven manually only when **both** position and heading follow are on.
 * - [allowFollowCamera] lets the host suppress camera tracking (e.g. map not ready) while
 *   keeping FAB toggle state.
 */
@Composable
fun rememberGeoVaultMapHeadingFollowFabBundle(
    map: GeoVaultBaseMap,
    userLocation: GeoVaultUserLocationCapability,
    allowFollowCamera: Boolean = true,
    orientationFollowFabId: String = "orientation_lock",
    orientationFollowFabOrder: Int = 35,
    orientationFollowContentDescription: String = "Follow my heading",
    onPermissionDenied: (() -> Unit)? = null,
): GeoVaultMapHeadingFollowFabBundle {
    val context = LocalContext.current
    var positionFollowDesired by rememberSaveable { mutableStateOf(false) }
    var headingFollowDesired by rememberSaveable { mutableStateOf(false) }
    fun followState(): GeoVaultMapCameraFollowState =
        GeoVaultMapCameraFollowState(positionFollowDesired, headingFollowDesired)
    val lastLogicalRef = remember {
        object {
            var last = GeoVaultMapCameraFollowState.NONE
        }
    }
    var pendingGrant by remember { mutableStateOf<GeoVaultMapHeadingFollowPendingGrant?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result[GeoVaultMapLocationPermission.FINE_AND_COARSE[0]] == true ||
            result[GeoVaultMapLocationPermission.FINE_AND_COARSE[1]] == true
        val pending = pendingGrant
        pendingGrant = null
        if (granted && pending == GeoVaultMapHeadingFollowPendingGrant.Heading) {
            val next = GeoVaultMapCameraFollowMachine.toggleHeadingOnTap(followState())
            positionFollowDesired = next.positionFollowDesired
            headingFollowDesired = next.headingFollowDesired
        } else if (!granted) {
            onPermissionDenied?.invoke()
        }
    }

    val latestFollowState = rememberUpdatedState(
        GeoVaultMapCameraFollowState(positionFollowDesired, headingFollowDesired),
    )
    val gestureListener = remember(map) {
        MapLibreMap.OnCameraMoveStartedListener { reason ->
            if (reason != MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) return@OnCameraMoveStartedListener
            val prev = latestFollowState.value
            if (!prev.positionFollowDesired && !prev.headingFollowDesired) return@OnCameraMoveStartedListener
            val next = GeoVaultMapCameraFollowMachine.afterUserGesture(prev)
            if (next != prev) {
                positionFollowDesired = next.positionFollowDesired
                headingFollowDesired = next.headingFollowDesired
            }
        }
    }
    DisposableEffect(map, gestureListener) {
        map.addOnCameraMoveStartedListener(gestureListener)
        onDispose { map.removeOnCameraMoveStartedListener(gestureListener) }
    }

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
        val effectiveLogical = if (allowFollowCamera) nextLogical else GeoVaultMapCameraFollowState.NONE
        val mode = effectiveLogical.toCameraMode()
        if (mode != CameraMode.NONE) {
            map.ensureInteractiveGestures()
            userLocation.setEnabled(true)
        }
        userLocation.setCameraMode(mode)
        lastLogicalRef.last = nextLogical
        onDispose { }
    }

    val plugin = userLocation as? MapLocationRendererPlugin
    DisposableEffect(map, plugin, positionFollowDesired, headingFollowDesired, allowFollowCamera) {
        val cameraShouldFollowHeading = positionFollowDesired && headingFollowDesired && allowFollowCamera
        if (!cameraShouldFollowHeading || plugin == null) {
            return@DisposableEffect onDispose { }
        }
        val listener: (Float) -> Unit = { bearingDegrees ->
            val libre = map.maplibreMap
            val fix = plugin.getLastLocation()
            if (libre != null && fix != null) {
                val current = libre.cameraPosition
                val next = CameraPosition.Builder(current)
                    .target(LatLng(fix.latitude, fix.longitude))
                    .bearing(bearingDegrees.toDouble())
                    .build()
                libre.moveCamera(CameraUpdateFactory.newCameraPosition(next))
            }
        }
        plugin.addBearingListener(listener)
        onDispose { plugin.removeBearingListener(listener) }
    }

    var hasZoomedToInitialFollow by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(positionFollowDesired, allowFollowCamera) {
        if (!positionFollowDesired || !allowFollowCamera) return@LaunchedEffect
        if (plugin == null) return@LaunchedEffect
        val libre = map.maplibreMap ?: return@LaunchedEffect
        val target: LatLng = plugin.getLastLocation()
            ?.let { LatLng(it.latitude, it.longitude) }
            ?: run {
                val current = LocationUpdates.getCurrentLatLngOnce(context, timeoutMs = 4000L)
                current ?: return@LaunchedEffect
            }
        val firstTime = !hasZoomedToInitialFollow
        val targetZoom = if (firstTime) INITIAL_FOLLOW_ZOOM else libre.cameraPosition.zoom
        val update = CameraUpdateFactory.newLatLngZoom(target, targetZoom)
        if (headingFollowDesired) {
            map.moveCameraWithPadding(update)
        } else {
            map.animateCameraWithPadding(update)
        }
        hasZoomedToInitialFollow = true
    }

    val clearForProgrammaticCameraMove: () -> Unit = {
        positionFollowDesired = false
        headingFollowDesired = false
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

    val orientationFab = GeoVaultMapFabAction(
        id = orientationFollowFabId,
        order = orientationFollowFabOrder,
        icon = if (headingFollowDesired) {
            GeoVaultMapFabIcon.Vector(Icons.Filled.Explore)
        } else {
            GeoVaultMapFabIcon.Vector(Icons.Outlined.Explore)
        },
        contentDescription = orientationFollowContentDescription,
        onTap = onHeadingTap,
    )

    return GeoVaultMapHeadingFollowFabBundle(
        headingFollowFab = orientationFab,
        clearForProgrammaticCameraMove = clearForProgrammaticCameraMove,
        positionFollowDesired = positionFollowDesired,
        headingFollowDesired = headingFollowDesired,
    )
}
