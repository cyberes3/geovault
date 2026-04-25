package com.geovault.common.maps.ui.camerafollow

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.GpsNotFixed
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

/** GPS + heading follow FAB pair with a single MapLibre camera owner. */
data class GeoVaultMapCameraFollowFabBundle(
    val gpsFollowFab: GeoVaultMapFabAction,
    val orientationFollowFab: GeoVaultMapFabAction,
    /**
     * Clears follow flags and camera tracking before host-driven camera moves (fit bounds,
     * selection zoom, navigation framing) so MapLibre does not fight programmatic animations.
     */
    val clearForProgrammaticCameraMove: () -> Unit,
    /** True while the GPS follow FAB is engaged (position follow / camera tracking as configured). */
    val gpsFollowDesired: Boolean,
    /** True while the heading / compass follow FAB is engaged. */
    val headingFollowDesired: Boolean,
)

private enum class GeoVaultMapCameraFollowPendingGrant {
    Gps,
    Heading,
}

/**
 * Zoom level the map snaps to the **first** time GPS follow is engaged in a session. Picked
 * to roughly match the survey data viewer "tap GPS lock" experience (city block scale —
 * close enough to the user to be useful, not so close that everything is texture).
 *
 * Subsequent engagements preserve whatever zoom the user has dialed in.
 */
private const val INITIAL_FOLLOW_ZOOM: Double = 10.0

/**
 * Coordinated GPS follow and compass / heading follow for one map.
 *
 * For a one-shot "go to my location" FAB without [CameraMode] follow, use
 * [com.geovault.common.maps.ui.recenter.rememberGeoVaultGpsRecenterFabAction].
 *
 * - One [MapLibreMap.OnCameraMoveStartedListener]: a user pan/zoom clears **GPS** follow only
 *   and keeps heading follow, matching the survey data viewer map.
 * - One saveable pair of booleans so both FABs agree with the single underlying [CameraMode].
 * - Map bearing is driven manually only when **both** GPS follow and heading follow are on
 *   (survey data viewer rule). When only heading is on, the puck rotates via the user-location
 *   plugin's existing heading sensor and the camera stays put — no double-sensor work and
 *   no per-frame map redraw fight with [CameraMode.TRACKING_COMPASS].
 * - Map bearing updates piggyback on the puck's [MapLocationRendererPlugin.addBearingListener]
 *   stream rather than spinning up a second [com.geovault.common.maps.location.HeadingSensor].
 * - Turning heading follow off snaps the map to north-up via [geoVaultResetCameraBearingAndTilt].
 * - [allowFollowCamera] lets the host suppress camera tracking (e.g. map not ready) while
 *   keeping FAB toggle state.
 */
@Composable
fun rememberGeoVaultMapCameraFollowFabBundle(
    map: GeoVaultBaseMap,
    userLocation: GeoVaultUserLocationCapability,
    allowFollowCamera: Boolean = true,
    gpsFollowFabId: String = "gps_follow",
    gpsFollowFabOrder: Int = 30,
    gpsFollowContentDescription: String = "Follow my location",
    orientationFollowFabId: String = "orientation_lock",
    orientationFollowFabOrder: Int = 35,
    orientationFollowContentDescription: String = "Follow my heading",
    onPermissionDenied: (() -> Unit)? = null,
): GeoVaultMapCameraFollowFabBundle {
    val context = LocalContext.current
    var gpsFollowDesired by rememberSaveable { mutableStateOf(false) }
    var headingFollowDesired by rememberSaveable { mutableStateOf(false) }
    fun followState(): GeoVaultMapCameraFollowState =
        GeoVaultMapCameraFollowState(gpsFollowDesired, headingFollowDesired)
    val lastLogicalRef = remember {
        object {
            var last = GeoVaultMapCameraFollowState.NONE
        }
    }
    var pendingGrant by remember { mutableStateOf<GeoVaultMapCameraFollowPendingGrant?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result[GeoVaultMapLocationPermission.FINE_AND_COARSE[0]] == true ||
            result[GeoVaultMapLocationPermission.FINE_AND_COARSE[1]] == true
        val pending = pendingGrant
        pendingGrant = null
        if (granted && pending != null) {
            // Re-run the same FAB-tap transitions through the state machine that would have
            // fired had the permission already been held — keeps the "heading FAB also engages
            // GPS follow" contract centralized in [GeoVaultMapCameraFollowMachine] instead of
            // duplicated here.
            val next = when (pending) {
                GeoVaultMapCameraFollowPendingGrant.Gps ->
                    GeoVaultMapCameraFollowMachine.toggleGpsOnTap(followState())
                GeoVaultMapCameraFollowPendingGrant.Heading ->
                    GeoVaultMapCameraFollowMachine.toggleHeadingOnTap(followState())
            }
            gpsFollowDesired = next.gpsFollowDesired
            headingFollowDesired = next.headingFollowDesired
        } else if (!granted) {
            onPermissionDenied?.invoke()
        }
    }

    val latestFollowState = rememberUpdatedState(
        GeoVaultMapCameraFollowState(gpsFollowDesired, headingFollowDesired),
    )
    val gestureListener = remember(map) {
        MapLibreMap.OnCameraMoveStartedListener { reason ->
            if (reason != MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) return@OnCameraMoveStartedListener
            val prev = latestFollowState.value
            if (!prev.gpsFollowDesired && !prev.headingFollowDesired) return@OnCameraMoveStartedListener
            val next = GeoVaultMapCameraFollowMachine.afterUserGesture(prev)
            if (next != prev) {
                gpsFollowDesired = next.gpsFollowDesired
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
        gpsFollowDesired,
        headingFollowDesired,
    ) {
        val nextLogical = GeoVaultMapCameraFollowState(gpsFollowDesired, headingFollowDesired)
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

    // android-common-maps parity: only push camera bearing while BOTH GPS follow and heading follow
    // are on (heading-alone just rotates the puck, no camera work). Subscribe to the puck's
    // existing heading sensor so we don't run a second SENSOR_DELAY_FASTEST stream — that was
    // the source of the freeze when the rotation FAB was tapped.
    //
    // The per-frame path bypasses [GeoVaultBaseMap.moveCameraWithPadding] and pokes MapLibre
    // directly: at 60 Hz the wrapper's per-call `resolveEffectiveMaxZoom()` source-id lookup,
    // zoom-clamp recompute, and rebuilding of `CameraPosition.Builder(position).zoom(clamped)`
    // add up to visible chop. Padding is preserved by reusing the current camera position.
    val plugin = userLocation as? MapLocationRendererPlugin
    DisposableEffect(map, plugin, gpsFollowDesired, headingFollowDesired, allowFollowCamera) {
        val cameraShouldFollowHeading = gpsFollowDesired && headingFollowDesired && allowFollowCamera
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

    // First time GPS follow is engaged in this session: zoom to a sensible default so the
    // user is not staring at a country-level view. Subsequent engagements only pan, preserving
    // whatever zoom the user dialed in. `rememberSaveable` keeps the flag across config changes
    // and process death (we treat the saved-state bag as "this session").
    var hasZoomedToInitialFollow by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(gpsFollowDesired, allowFollowCamera) {
        if (!gpsFollowDesired || !allowFollowCamera) return@LaunchedEffect
        if (plugin == null) return@LaunchedEffect
        val libre = map.maplibreMap ?: return@LaunchedEffect
        val target: LatLng = plugin.getLastLocation()
            ?.let { LatLng(it.latitude, it.longitude) }
            ?: run {
                // No cached fix yet — request one. We don't suspend forever; if it never
                // resolves (permission revoked mid-flight, no provider, etc.) we just give up.
                val current = LocationUpdates.getCurrentLatLngOnce(context, timeoutMs = 4000L)
                current ?: return@LaunchedEffect
            }
        val firstTime = !hasZoomedToInitialFollow
        val targetZoom = if (firstTime) INITIAL_FOLLOW_ZOOM else libre.cameraPosition.zoom
        val update = CameraUpdateFactory.newLatLngZoom(target, targetZoom)
        if (headingFollowDesired) {
            // Bearing pump owns the camera at 60 Hz — animating here would fight it. Snap.
            map.moveCameraWithPadding(update)
        } else {
            // GPS-only: a smooth pan looks better than a jump. The location component (in
            // [CameraMode.TRACKING]) takes over re-centering on subsequent fixes.
            map.animateCameraWithPadding(update)
        }
        hasZoomedToInitialFollow = true
    }

    val clearForProgrammaticCameraMove: () -> Unit = {
        gpsFollowDesired = false
        headingFollowDesired = false
    }

    val onGpsTap: () -> Unit = {
        if (context.geoVaultMapHasFineOrCoarseLocation()) {
            val next = GeoVaultMapCameraFollowMachine.toggleGpsOnTap(followState())
            gpsFollowDesired = next.gpsFollowDesired
            headingFollowDesired = next.headingFollowDesired
        } else {
            pendingGrant = GeoVaultMapCameraFollowPendingGrant.Gps
            permissionLauncher.launch(GeoVaultMapLocationPermission.FINE_AND_COARSE)
        }
    }

    val onHeadingTap: () -> Unit = {
        if (context.geoVaultMapHasFineOrCoarseLocation()) {
            val next = GeoVaultMapCameraFollowMachine.toggleHeadingOnTap(followState())
            gpsFollowDesired = next.gpsFollowDesired
            headingFollowDesired = next.headingFollowDesired
        } else {
            pendingGrant = GeoVaultMapCameraFollowPendingGrant.Heading
            permissionLauncher.launch(GeoVaultMapLocationPermission.FINE_AND_COARSE)
        }
    }

    val gpsFab = GeoVaultMapFabAction(
        id = gpsFollowFabId,
        order = gpsFollowFabOrder,
        icon = if (gpsFollowDesired) {
            GeoVaultMapFabIcon.Vector(Icons.Filled.GpsFixed)
        } else {
            GeoVaultMapFabIcon.Vector(Icons.Outlined.GpsNotFixed)
        },
        contentDescription = gpsFollowContentDescription,
        onTap = onGpsTap,
    )
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

    return GeoVaultMapCameraFollowFabBundle(
        gpsFollowFab = gpsFab,
        orientationFollowFab = orientationFab,
        clearForProgrammaticCameraMove = clearForProgrammaticCameraMove,
        gpsFollowDesired = gpsFollowDesired,
        headingFollowDesired = headingFollowDesired,
    )
}
