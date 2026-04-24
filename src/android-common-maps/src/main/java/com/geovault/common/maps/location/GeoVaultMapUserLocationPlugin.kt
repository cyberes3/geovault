package com.geovault.common.maps.location

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Builds the canonical GeoVault user-location plugin (chevron-in-circle compass puck). Use
 * this in contexts that don't have a Compose tree (e.g. pure Activities). Callers are
 * responsible for wiring [HeadingSensor] if they want the chevron to rotate with the device
 * even when GPS is not providing a bearing — Compose hosts should prefer
 * [rememberGeoVaultMapUserLocationPlugin] which does this automatically.
 */
fun createGeoVaultMapUserLocationPlugin(context: Context): MapLocationRendererPlugin {
    return MapLocationRendererPlugin(
        context = context,
        config = GeoVaultLocationPuckPresets.userLocationPuck(),
        autoEnableLocationComponent = true,
    )
}

/**
 * Compose-friendly entry point for the shared user-location puck.
 *
 * In addition to creating the plugin, this composable attaches a [HeadingSensor] scoped to
 * the calling composition: bearings from the rotation-vector sensor are forwarded into the
 * plugin via [MapLocationRendererPlugin.updateBearing] while the composable is in the tree,
 * and sensor polling stops (plus any bearing override is cleared) on disposal. Devices
 * without a rotation-vector sensor silently skip sensor start — MapLibre's internal compass
 * engine still drives rotation when available.
 */
@Composable
fun rememberGeoVaultMapUserLocationPlugin(
    context: Context = LocalContext.current,
): MapLocationRendererPlugin {
    val plugin = remember(context) { createGeoVaultMapUserLocationPlugin(context = context) }
    val headingSensor = remember(context) { HeadingSensor(context) }
    DisposableEffect(headingSensor, plugin) {
        if (headingSensor.isAvailable) {
            headingSensor.start { bearing -> plugin.updateBearing(bearing) }
        }
        onDispose {
            headingSensor.stop()
            plugin.clearBearingOverride()
        }
    }
    return plugin
}
