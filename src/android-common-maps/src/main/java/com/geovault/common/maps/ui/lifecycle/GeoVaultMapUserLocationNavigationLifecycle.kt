package com.geovault.common.maps.ui.lifecycle

import android.location.Location
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import com.geovault.common.maps.location.GeoVaultMapGpsNotificationPermissionEffect
import com.geovault.common.maps.location.MapLocationRendererPlugin
import com.geovault.common.maps.navigation.GeoVaultNavigationToPointPlugin

/**
 * GPS stream, puck enable, optional navigation distance bridge, and accuracy circle.
 *
 * Permission and background-stream policy are folded into [shouldStreamGps] /
 * [shouldEnablePuck] by [com.geovault.common.maps.ui.location.GeoVaultMapLocationSessionPolicy]
 * (or a host overlay). Continuous GPS is owned by the shared location engine / FGS.
 *
 * When [shouldStreamGps] becomes true, [GeoVaultMapGpsNotificationPermissionEffect] requests
 * notification permission so the location FGS notification can be shown.
 *
 * [navigation] may be null for hosts that only need the puck/stream (for example Places).
 */
@Composable
fun GeoVaultMapUserLocationNavigationLifecycle(
    userLocation: MapLocationRendererPlugin,
    shouldStreamGps: Boolean,
    shouldEnablePuck: Boolean,
    showAccuracyCircle: Boolean,
    navigation: GeoVaultNavigationToPointPlugin? = null,
    gpsIntervalMs: Long = 1_000L,
    onEachLocationFix: ((Location) -> Unit)? = null,
) {
    val onEachFix = rememberUpdatedState(onEachLocationFix)

    GeoVaultMapGpsNotificationPermissionEffect(requestWhen = shouldStreamGps)

    LaunchedEffect(userLocation, shouldEnablePuck) {
        userLocation.setEnabled(shouldEnablePuck)
    }

    DisposableEffect(userLocation, shouldStreamGps, gpsIntervalMs) {
        if (shouldStreamGps) {
            userLocation.startRenderingGpsLocation(intervalMs = gpsIntervalMs)
        }
        onDispose { userLocation.stopRenderingGpsLocation() }
    }

    LaunchedEffect(userLocation, showAccuracyCircle) {
        userLocation.setAccuracyCircleVisible(showAccuracyCircle)
    }

    DisposableEffect(userLocation, navigation) {
        val listener: (Location) -> Unit = { loc ->
            navigation?.updateUserLocation(loc.latitude, loc.longitude)
            onEachFix.value?.invoke(loc)
        }
        userLocation.addLocationListener(listener)
        onDispose { userLocation.removeLocationListener(listener) }
    }
}
