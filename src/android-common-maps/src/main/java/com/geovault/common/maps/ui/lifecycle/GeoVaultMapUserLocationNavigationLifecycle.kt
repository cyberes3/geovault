package com.geovault.common.maps.ui.lifecycle

import android.location.Location
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import com.geovault.common.maps.location.MapLocationRendererPlugin
import com.geovault.common.maps.navigation.GeoVaultNavigationToPointPlugin

/**
 * GPS stream, puck enable, navigation distance bridge, and accuracy circle — shared by Survey
 * and NGS map routes. Permission is folded into [shouldStreamGps] and [shouldEnablePuck] by
 * each app's policy; use [rememberGeoVaultMapLocationPermissionState] at the route level for
 * lifecycle-safe permission reads.
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
    val onEachFix = rememberUpdatedState(onEachLocationFix)

    LaunchedEffect(userLocation, shouldEnablePuck) {
        userLocation.setEnabled(shouldEnablePuck)
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
