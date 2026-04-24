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
) : GeoVaultMapPlugin, GeoVaultUserLocationCapability {
    private val appContext = context.applicationContext
    private var map: MapLibreMap? = null
    private var updatesSession: LocationUpdates.LocationUpdatesSession? = null
    private var accuracyCircleVisible: Boolean = config.accuracyAlpha > 0f
    private var lastLocation: Location? = null
    private var overrideBearing: Float? = null
    private var puckBackgroundTranslucent: Boolean = false
    private val locationListeners: MutableList<(Location) -> Unit> = mutableListOf()

    override fun onMapAttached(map: MapLibreMap) {
        this.map = map
    }

    override fun onMapDetached() {
        stopRenderingGpsLocation()
        map = null
    }

    override fun onStyleLoaded(map: MapLibreMap, style: Style) {
        this.map = map
        if (!autoEnableLocationComponent) return
        activateOrApply(map, style)
    }

    override fun setEnabled(enabled: Boolean) {
        val mapValue = map ?: return
        LocationComponentHelper.setEnabled(mapValue, enabled)
    }

    override fun setCameraTracking(enabled: Boolean) {
        val mapValue = map ?: return
        LocationComponentHelper.setCameraTracking(mapValue, enabled)
    }

    override fun setCameraMode(cameraMode: Int) {
        val mapValue = map ?: return
        LocationComponentHelper.setCameraMode(mapValue, cameraMode)
    }

    /**
     * Shows/hides the MapLibre location accuracy circle without rebuilding the map.
     * Applies immediately when the location component is active.
     */
    override fun setAccuracyCircleVisible(visible: Boolean) {
        accuracyCircleVisible = visible
        val mapValue = map ?: return
        val locationComponent = mapValue.locationComponent
        if (!locationComponent.isLocationComponentActivated) return
        LocationComponentHelper.applyStyle(mapValue, appContext, effectiveConfig())
    }

    override fun isAccuracyCircleVisible(): Boolean = accuracyCircleVisible

    /**
     * Swap the puck's disc background between the solid and translucent variants supplied in
     * [LocationComponentHelper.Config]. Callers use this to dim the puck when it is sitting
     * over a feature / point symbol so the underlying render is still legible.
     *
     * No-op when the configured [LocationComponentHelper.Config.backgroundDrawableTranslucent]
     * is `null` (i.e. the current preset doesn't ship a translucent variant) or when the
     * requested value already matches the current state, so hosts can safely call this on
     * every camera-idle / location-update tick without thrashing MapLibre style applies.
     */
    fun setPuckBackgroundTranslucent(translucent: Boolean) {
        if (config.backgroundDrawableTranslucent == null) return
        if (puckBackgroundTranslucent == translucent) return
        puckBackgroundTranslucent = translucent
        val mapValue = map ?: return
        if (!mapValue.locationComponent.isLocationComponentActivated) return
        LocationComponentHelper.applyStyle(mapValue, appContext, effectiveConfig())
    }

    fun isPuckBackgroundTranslucent(): Boolean = puckBackgroundTranslucent

    @SuppressLint("MissingPermission")
    override fun renderLocation(location: Location) {
        lastLocation = location
        val applied = applyBearingOverride(location)
        val mapValue = map ?: return
        LocationComponentHelper.forceLocation(mapValue, applied)
        locationListeners.toList().forEach { it(applied) }
    }

    /**
     * Force a heading update without a new GPS fix. The last-known [Location] is re-emitted
     * with this bearing so [RenderMode.COMPASS] pucks rotate as the device turns, even when
     * the user is stationary and GPS callbacks are paused.
     *
     * No-op until at least one call to [renderLocation] has happened (nothing to rotate).
     */
    @SuppressLint("MissingPermission")
    fun updateBearing(bearingDegrees: Float) {
        overrideBearing = bearingDegrees
        val last = lastLocation ?: return
        val applied = applyBearingOverride(last)
        val mapValue = map ?: return
        LocationComponentHelper.forceLocation(mapValue, applied)
    }

    /**
     * Clear any bearing override so subsequent renders use the GPS-provided bearing (or
     * nothing, on providers that don't set one).
     */
    fun clearBearingOverride() {
        overrideBearing = null
    }

    /**
     * Observe every location pushed through [renderLocation] (including synthesized fixes
     * from [updateBearing]). Subscribers run on whichever thread posted the update; the host
     * is expected to hop to its own dispatcher if needed.
     */
    fun addLocationListener(listener: (Location) -> Unit) {
        locationListeners += listener
    }

    fun removeLocationListener(listener: (Location) -> Unit) {
        locationListeners -= listener
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

    override fun onPluginDestroyed() {
        stopRenderingGpsLocation()
        locationListeners.clear()
        lastLocation = null
        overrideBearing = null
        puckBackgroundTranslucent = false
        map = null
    }

    private fun applyBearingOverride(source: Location): Location {
        val override = overrideBearing ?: return source
        // Preserve caller-provided metadata (time, provider, accuracy) and only substitute bearing.
        // We do not mutate [source] because callers may retain references.
        return Location(source).apply {
            bearing = override
        }
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
        val accuracyAlpha = if (accuracyCircleVisible) config.accuracyAlpha else 0f
        return config.copy(
            accuracyAlpha = accuracyAlpha,
            useTranslucentBackground = puckBackgroundTranslucent,
        )
    }

}
