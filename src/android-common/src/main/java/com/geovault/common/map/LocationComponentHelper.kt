package com.geovault.common.map

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import androidx.annotation.DrawableRes
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

/**
 * Shared LocationComponent activation/config for GeoVault maps.
 */
object LocationComponentHelper {
    data class Config(
        val accuracyColor: Int,
        val accuracyAlpha: Float = 0.25f,
        @param:DrawableRes val backgroundDrawable: Int? = null,
        @param:DrawableRes val foregroundDrawable: Int? = null,
        val renderMode: Int = RenderMode.NORMAL
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
            .minZoomIconScale(0.45f)
            .maxZoomIconScale(0.75f)
            .accuracyColor(config.accuracyColor)
            .accuracyAlpha(config.accuracyAlpha)
        config.backgroundDrawable?.let {
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

