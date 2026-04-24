package com.geovault.common.maps.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

object LocationComponentHelper {
    private const val DEFAULT_LOCATION_ICON_SCALE = 0.45f

    /**
     * Styling contract for MapLibre's location component.
     *
     * The [backgroundDrawable] is the static disc rendered underneath the puck and the
     * [backgroundDrawableTranslucent] is an optional alpha-attenuated variant of the same
     * disc. Hosts toggle between the two at runtime (via
     * [MapLocationRendererPlugin.setPuckBackgroundTranslucent]) to keep an underlying
     * feature / point visible when the puck happens to overlap it. When the translucent
     * drawable is `null` the toggle is a no-op.
     */
    data class Config(
        val accuracyColor: Int,
        val accuracyAlpha: Float = 0.25f,
        @param:DrawableRes val backgroundDrawable: Int? = null,
        @param:DrawableRes val backgroundDrawableTranslucent: Int? = null,
        @param:DrawableRes val foregroundDrawable: Int? = null,
        val iconScale: Float = DEFAULT_LOCATION_ICON_SCALE,
        val renderMode: Int = RenderMode.NORMAL,
        val useTranslucentBackground: Boolean = false,
    )

    @SuppressLint("MissingPermission")
    fun activate(map: MapLibreMap, style: Style, context: Context, config: Config) {
        val activationOptions = LocationComponentActivationOptions.builder(context, style)
            .locationComponentOptions(buildOptions(context, config))
            .useDefaultLocationEngine(false)
            .useSpecializedLocationLayer(true)
            .build()

        map.locationComponent.activateLocationComponent(activationOptions)
        map.locationComponent.isLocationComponentEnabled = true
        map.locationComponent.renderMode = config.renderMode
        map.locationComponent.cameraMode = CameraMode.NONE
    }

    fun applyStyle(map: MapLibreMap, context: Context, config: Config) {
        map.locationComponent.applyStyle(buildOptions(context, config))
        map.locationComponent.renderMode = config.renderMode
    }

    fun setEnabled(map: MapLibreMap, enabled: Boolean) {
        map.locationComponent.isLocationComponentEnabled = enabled
        if (!enabled) {
            map.locationComponent.cameraMode = CameraMode.NONE
        }
    }

    fun setCameraTracking(map: MapLibreMap, enabled: Boolean) {
        map.locationComponent.cameraMode = if (enabled) CameraMode.TRACKING else CameraMode.NONE
    }

    /**
     * Switch the MapLibre camera-tracking mode directly. Used by the GPS-follow and
     * orientation-lock FABs to toggle between NONE, TRACKING, and TRACKING_COMPASS without
     * re-activating the location component.
     */
    fun setCameraMode(map: MapLibreMap, cameraMode: Int) {
        map.locationComponent.cameraMode = cameraMode
    }

    @SuppressLint("MissingPermission")
    fun forceLocation(map: MapLibreMap, location: Location) {
        map.locationComponent.forceLocationUpdate(location)
    }

    private fun buildOptions(context: Context, config: Config): LocationComponentOptions {
        val optionsBuilder = LocationComponentOptions.builder(context)
            .accuracyAnimationEnabled(false)
            .compassAnimationEnabled(false)
            .trackingAnimationDurationMultiplier(0f)
            .enableStaleState(false)
            .elevation(0f)
            .minZoomIconScale(config.iconScale)
            .maxZoomIconScale(config.iconScale)
            .accuracyColor(config.accuracyColor)
            .accuracyAlpha(config.accuracyAlpha)
        val effectiveBackground = when {
            config.useTranslucentBackground && config.backgroundDrawableTranslucent != null ->
                config.backgroundDrawableTranslucent
            else -> config.backgroundDrawable
        }
        effectiveBackground?.let {
            optionsBuilder
                .backgroundDrawable(it)
                .backgroundDrawableStale(it)
        }
        config.foregroundDrawable?.let {
            optionsBuilder
                .foregroundDrawable(it)
                .foregroundDrawableStale(it)
                .gpsDrawable(it)
                .bearingDrawable(it)
        }
        return optionsBuilder.build()
    }
}
