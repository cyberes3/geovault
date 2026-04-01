package com.geovault.common.maps.location

import android.annotation.SuppressLint
import androidx.annotation.ColorInt
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

object LocationComponentHelper {
    data class Config(
        @param:ColorInt val accuracyColor: Int,
        val accuracyAlpha: Float = 0.2f,
        val pulseEnabled: Boolean = false,
    )

    @SuppressLint("MissingPermission")
    fun activate(context: android.content.Context, map: MapLibreMap, style: Style, config: Config) {
        val options = LocationComponentOptions.builder(context)
            .accuracyColor(config.accuracyColor)
            .accuracyAlpha(config.accuracyAlpha)
            .pulseEnabled(config.pulseEnabled)
            .trackingAnimationDurationMultiplier(1f)
            .build()
        val activation = LocationComponentActivationOptions.builder(context, style)
            .locationComponentOptions(options)
            .build()
        val component = map.locationComponent
        component.activateLocationComponent(activation)
        component.isLocationComponentEnabled = true
    }
}
