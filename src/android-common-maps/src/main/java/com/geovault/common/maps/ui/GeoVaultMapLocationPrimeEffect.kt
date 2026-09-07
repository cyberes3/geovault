package com.geovault.common.maps.ui

import android.location.Location
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.geovault.common.maps.location.GeoVaultUserLocationCapability
import com.geovault.common.maps.location.LocationUpdates

@Composable
fun GeoVaultMapLocationPrimeEffect(
    location: GeoVaultUserLocationCapability,
    shouldStreamGps: Boolean,
    providerName: String,
    timeoutMs: Long = 4000L,
    accuracyMeters: Float = 12f,
) {
    val context = LocalContext.current
    LaunchedEffect(location, shouldStreamGps, providerName) {
        if (!shouldStreamGps) return@LaunchedEffect
        val latLng = LocationUpdates.getCurrentLatLngOnce(context, timeoutMs = timeoutMs)
            ?: return@LaunchedEffect
        val synthetic = Location(providerName).apply {
            latitude = latLng.latitude
            longitude = latLng.longitude
            accuracy = accuracyMeters
            time = System.currentTimeMillis()
        }
        location.renderLocation(synthetic)
    }
}
