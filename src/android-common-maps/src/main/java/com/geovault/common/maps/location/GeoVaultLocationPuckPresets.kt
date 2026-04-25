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
 * via [RenderMode.COMPASS], driven by the smoothed
 * [com.geovault.common.maps.location.HeadingSensor] forwarded into MapLibre through our
 * [GeoVaultHeadingCompassEngine] (installed by [MapLocationRendererPlugin]). The disc stays
 * pinned over the user's GPS position.
 */
object GeoVaultLocationPuckPresets {
    /**
     * The single GeoVault user-location puck.
     *
     * Render mode is [RenderMode.COMPASS] — not [RenderMode.GPS] — for two non-negotiable
     * reasons:
     *
     * 1. **Accuracy circle.** MapLibre's `IndicatorLocationLayerRenderer.setImages(...)`
     *    hard-codes `setAccuracyRadius(0f)` whenever the render mode flips to GPS, AND
     *    `LocationLayerController.getAnimationListeners(...)` only registers the
     *    `ANIMATOR_LAYER_ACCURACY` listener for `COMPASS` and `NORMAL` modes. The accuracy
     *    halo is structurally unrenderable in GPS mode.
     * 2. **One source of truth for puck rotation.** GPS mode rotates the puck from the
     *    `Location.bearing` field via the GPS bearing animator (and is animated by elapsed
     *    time between `forceLocationUpdate` calls); COMPASS mode rotates it via whatever
     *    [org.maplibre.android.location.CompassEngine] is installed. We override the engine
     *    in [MapLocationRendererPlugin] to be our own [GeoVaultHeadingCompassEngine], which
     *    pushes the same smoothed [com.geovault.common.maps.location.HeadingSensor] stream
     *    that drives the camera bearing. That keeps the puck and the map locked together
     *    instead of drifting apart on two independent sensor pipelines.
     *
     * Composed of two layers so the chevron's apex points outward from the user's position:
     * - Background (static): `gv_common_ic_user_location_puck_circle` — white disc, 1dp black
     *   stroke. `gv_common_ic_user_location_puck_circle_translucent` is the 85%-alpha
     *   variant that hosts can swap in via
     *   [MapLocationRendererPlugin.setPuckBackgroundTranslucent] when the puck overlaps an
     *   underlying feature / point symbol.
     * - Foreground (rotates): `gv_common_ic_user_location_arrow` — blue chevron anchored at
     *   the top of a 32dp viewport. Wired to both `foregroundDrawable` and `bearingDrawable`
     *   in [LocationComponentHelper] so the bearing image (the merged disc + chevron) is
     *   what visibly rotates with compass updates.
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
