package com.geovault.common.maps.ui.lifecycle

import android.location.Location
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.geovault.common.maps.location.LocationUpdates
import com.geovault.common.maps.location.MapLocationRendererPlugin
import com.geovault.common.maps.navigation.GeoVaultNavigationToPointPlugin

/**
 * GPS stream, puck enable, cold-start prime, navigation distance bridge, and accuracy circle —
 * shared by Survey and NGS map routes. Permission is folded into [shouldStreamGps] /
 * [shouldEnablePuck] by each app’s policy; use [rememberGeoVaultMapLocationPermissionState] at
 * the route level for lifecycle-safe permission reads.
 */
@Composable
fun GeoVaultMapUserLocationNavigationLifecycle(
    userLocation: MapLocationRendererPlugin,
    navigation: GeoVaultNavigationToPointPlugin,
    shouldStreamGps: Boolean,
    shouldEnablePuck: Boolean,
    showAccuracyCircle: Boolean,
    gpsIntervalMs: Long = 1_000L,
    onEachLocationFix: ((Location) -> Unit)? = null,
) {
    val context = LocalContext.current
    val onEachFix = rememberUpdatedState(onEachLocationFix)

    LaunchedEffect(userLocation, shouldEnablePuck, shouldStreamGps) {
        userLocation.setEnabled(shouldEnablePuck)
        if (!shouldStreamGps) return@LaunchedEffect
        val latLng = LocationUpdates.getCurrentLatLngOnce(context, timeoutMs = 4000L)
            ?: return@LaunchedEffect
        val synthetic = Location("geovault-map-user-lifecycle-prime").apply {
            latitude = latLng.latitude
            longitude = latLng.longitude
            accuracy = 12f
            time = System.currentTimeMillis()
        }
        userLocation.renderLocation(synthetic)
    }

    DisposableEffect(userLocation, shouldStreamGps) {
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
            navigation.updateUserLocation(loc.latitude, loc.longitude)
            onEachFix.value?.invoke(loc)
        }
        userLocation.addLocationListener(listener)
        onDispose { userLocation.removeLocationListener(listener) }
    }
}
