package com.geovault.common.maps.location

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Default [MapLocationRendererPlugin] for GeoVault maps (same puck, accuracy ring, and MapLibre options as the main map tab).
 * Prefer this over constructing [MapLocationRendererPlugin] in apps so user-location styling stays centralized.
 */
fun createGeoVaultMapUserLocationPlugin(context: Context): MapLocationRendererPlugin {
    return MapLocationRendererPlugin(
        context = context,
        config = GeoVaultLocationPuckPresets.blueUserLocation(),
        autoEnableLocationComponent = true,
    )
}

@Composable
fun rememberGeoVaultMapUserLocationPlugin(
    context: Context = LocalContext.current,
): MapLocationRendererPlugin {
    return remember(context) {
        createGeoVaultMapUserLocationPlugin(context = context)
    }
}
