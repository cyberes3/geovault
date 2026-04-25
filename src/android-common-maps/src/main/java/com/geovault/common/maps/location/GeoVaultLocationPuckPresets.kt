package com.geovault.common.maps.location

import androidx.compose.ui.graphics.toArgb
import com.geovault.common.maps.R
import com.geovault.common.ui.theme.GeoVaultColorTokens
import org.maplibre.android.location.modes.RenderMode

/**
 * Styling for the shared GeoVault user-location puck. Apps normally obtain a configured
 * [MapLocationRendererPlugin] via [createGeoVaultMapUserLocationPlugin] /
 * [rememberGeoVaultMapUserLocationPlugin] instead of constructing one manually.
 *
 * There is intentionally one puck across all GeoVault apps (survey, tracker, places): a blue
 * chevron inside a white disc with a thin black border. The chevron rotates to device heading
 * via [RenderMode.COMPASS] while the disc stays pinned over the user's GPS position.
 */
object GeoVaultLocationPuckPresets {
    /**
     * The single GeoVault user-location puck. Uses [RenderMode.COMPASS] so MapLibre rotates
     * the foreground chevron around the drawable's geometric center based on the compass
     * engine (or a bearing forwarded via [MapLocationRendererPlugin.updateBearing]).
     *
     * Composed of two layers so the chevron's apex points outward from the user's position:
     * - Background (static): `gv_common_ic_user_location_puck_circle` — white disc, 1dp black
     *   stroke. `gv_common_ic_user_location_puck_circle_translucent` is the 85%-alpha
     *   variant that hosts can swap in via
     *   [MapLocationRendererPlugin.setPuckBackgroundTranslucent] when the puck overlaps an
     *   underlying feature / point symbol.
     * - Foreground (rotates): `gv_common_ic_user_location_arrow` — blue chevron anchored at
     *   the top of a 32dp viewport.
     */
    fun userLocationPuck(
        accuracyAlpha: Float = 0.25f,
    ): LocationComponentHelper.Config {
        return LocationComponentHelper.Config(
            accuracyColor = GeoVaultColorTokens.MainBlue.toArgb(),
            accuracyAlpha = accuracyAlpha,
            backgroundDrawable = R.drawable.gv_common_ic_user_location_puck_circle,
            backgroundDrawableTranslucent =
                R.drawable.gv_common_ic_user_location_puck_circle_translucent,
            foregroundDrawable = R.drawable.gv_common_ic_user_location_arrow,
            renderMode = RenderMode.COMPASS,
        )
    }
}
