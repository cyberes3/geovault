package com.geovault.common.maps.location

import com.geovault.common.maps.R
import com.geovault.common.ui.theme.GeoVaultColorTokens
import org.maplibre.android.location.modes.RenderMode

/**
 * Styling values for MapLibre’s location component. Apps should normally obtain a configured
 * [MapLocationRendererPlugin] via [createGeoVaultMapUserLocationPlugin] / [rememberGeoVaultMapUserLocationPlugin]
 * instead of building the plugin manually.
 */
object GeoVaultLocationPuckPresets {
    fun blueUserLocation(
        accuracyAlpha: Float = 0.25f,
    ): LocationComponentHelper.Config {
        return LocationComponentHelper.Config(
            accuracyColor = GeoVaultColorTokens.PRIMARY_BLUE_INT,
            accuracyAlpha = accuracyAlpha,
            // Explicit transparent background avoids MapLibre fallback background circles.
            backgroundDrawable = R.drawable.gv_common_ic_location_transparent_bg,
            foregroundDrawable = R.drawable.gv_common_ic_user_location,
            iconScale = 0.8f,
            renderMode = RenderMode.NORMAL,
        )
    }
}
