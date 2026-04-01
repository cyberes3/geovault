package com.geovault.common.maps.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.geovault.common.maps.core.GeoVaultMapPlugin
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

/**
 * Simple location renderer for GeoVault maps.
 * Uses MapLibre's built-in location marker + accuracy rendering via LocationComponentHelper.
 */
class MapLocationRendererPlugin(
    context: Context,
    private val config: LocationComponentHelper.Config,
    private val autoEnableLocationComponent: Boolean = true,
) : GeoVaultMapPlugin {
    private val appContext = context.applicationContext
    private var map: MapLibreMap? = null
    private var updatesSession: LocationUpdates.LocationUpdatesSession? = null
    private var accuracyCircleVisible: Boolean = config.accuracyAlpha > 0f

    override fun onMapReady(map: MapLibreMap) {
        this.map = map
    }

    override fun onStyleLoaded(map: MapLibreMap, style: Style) {
        this.map = map
        if (!autoEnableLocationComponent) return
        activateOrApply(map, style)
    }

    fun setEnabled(enabled: Boolean) {
        val mapValue = map ?: return
        LocationComponentHelper.setEnabled(mapValue, enabled)
    }

    fun setCameraTracking(enabled: Boolean) {
        val mapValue = map ?: return
        LocationComponentHelper.setCameraTracking(mapValue, enabled)
    }

    /**
     * Shows/hides the MapLibre location accuracy circle without rebuilding the map.
     * Applies immediately when the location component is active.
     */
    fun setAccuracyCircleVisible(visible: Boolean) {
        accuracyCircleVisible = visible
        val mapValue = map ?: return
        val locationComponent = mapValue.locationComponent
        if (!locationComponent.isLocationComponentActivated) return
        LocationComponentHelper.applyStyle(mapValue, appContext, effectiveConfig())
    }

    fun isAccuracyCircleVisible(): Boolean = accuracyCircleVisible

    @SuppressLint("MissingPermission")
    fun renderLocation(location: Location) {
        val mapValue = map ?: return
        LocationComponentHelper.forceLocation(mapValue, location)
    }

    /**
     * Starts GPS updates and feeds them directly into the MapLibre location renderer.
     * Caller must ensure location permissions are granted before calling.
     */
    @SuppressLint("MissingPermission")
    fun startRenderingGpsLocation(intervalMs: Long = 2000L) {
        stopRenderingGpsLocation()
        updatesSession = LocationUpdates.startLocationUpdates(appContext, intervalMs) { _, location ->
            if (location != null) {
                renderLocation(location)
            }
        }
    }

    fun stopRenderingGpsLocation() {
        updatesSession?.stop()
        updatesSession = null
    }

    override fun onDestroy() {
        stopRenderingGpsLocation()
    }

    @SuppressLint("MissingPermission")
    private fun activateOrApply(map: MapLibreMap, style: Style) {
        val locationComponent = map.locationComponent
        if (locationComponent.isLocationComponentActivated) {
            LocationComponentHelper.applyStyle(map, appContext, effectiveConfig())
            return
        }
        LocationComponentHelper.activate(map, style, appContext, effectiveConfig())
    }

    private fun effectiveConfig(): LocationComponentHelper.Config {
        return if (accuracyCircleVisible) {
            config
        } else {
            config.copy(accuracyAlpha = 0f)
        }
    }
}
