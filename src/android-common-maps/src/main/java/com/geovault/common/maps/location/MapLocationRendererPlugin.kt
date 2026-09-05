package com.geovault.common.maps.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.util.Log
import com.geovault.common.maps.core.GeoVaultMapPlugin
import org.maplibre.android.location.LocationComponentConstants.FOREGROUND_LAYER
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
    private var updatesSession: GeoVaultMapGpsLocationSession? = null
    private var accuracyCircleVisible: Boolean = config.accuracyAlpha > 0f
    private var lastLocation: Location? = null
    private var puckBackgroundTranslucent: Boolean = false
    private val renderState = LocationComponentRenderState()
    private val locationListeners: MutableList<(Location) -> Unit> = mutableListOf()
    private val bearingListeners: MutableList<(Float) -> Unit> = mutableListOf()
    private val headingCompassEngine = GeoVaultHeadingCompassEngine()

    override fun onMapAttached(map: MapLibreMap) {
        this.map = map
    }

    override fun onMapDetached() {
        stopRenderingGpsLocation()
        map = null
    }

    override fun onStyleWillChange(map: MapLibreMap, currentStyle: Style?) {
        renderState.markStyleBindingStale()
    }

    override fun onStyleLoaded(map: MapLibreMap, style: Style) {
        this.map = map
        if (!autoEnableLocationComponent) return
        bindToStyle(map, style)
    }

    override fun setEnabled(enabled: Boolean) {
        renderState.setEnabled(enabled)
        val mapValue = map ?: return
        LocationComponentHelper.setEnabled(mapValue, enabled)
    }

    override fun setCameraTracking(enabled: Boolean) {
        renderState.setCameraTracking(enabled)
        val mapValue = map ?: return
        LocationComponentHelper.setCameraTracking(mapValue, enabled)
    }

    override fun setCameraMode(cameraMode: Int) {
        renderState.setCameraMode(cameraMode)
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

    /**
     * The last location passed to [renderLocation], or `null` if no fix has been applied yet.
     * Hosts (e.g. camera fits) can use this between GPS callbacks.
     */
    fun getLastLocation(): Location? = lastLocation

    @SuppressLint("MissingPermission")
    override fun renderLocation(location: Location) {
        lastLocation = location
        val mapValue = map ?: return
        LocationComponentHelper.forceLocation(mapValue, location)
        locationListeners.toList().forEach { it(location) }
    }

    /**
     * Push a new device heading (degrees clockwise from north). The puck's
     * [org.maplibre.android.location.modes.RenderMode.COMPASS] bearing is driven through our
     * installed [GeoVaultHeadingCompassEngine] so MapLibre's internal animator interpolates
     * over the elapsed time between successive pushes — at our ~60 Hz emit cadence this
     * yields continuously-smooth rotation that exactly tracks the camera bearing (which is
     * fed from the same sensor stream via [addBearingListener]).
     *
     * Notifies [addBearingListener] subscribers so other map subsystems (e.g. camera bearing
     * follow) can share this single sensor stream instead of running their own. Does NOT fire
     * [addLocationListener] subscribers — bearing-only updates have no new lat/lon and would
     * otherwise spam any per-fix consumer (nav distance label, telemetry, etc.). And does NOT
     * call `forceLocationUpdate`, which would re-trigger MapLibre's accuracy-radius animator
     * with the same value (wasted work) on every sensor frame.
     */
    fun updateBearing(bearingDegrees: Float) {
        headingCompassEngine.pushHeading(bearingDegrees)
        bearingListeners.toList().forEach { it(bearingDegrees) }
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
     * Observe every smoothed bearing pushed through [updateBearing]. Lets one
     * [HeadingSensor] drive both the puck and any camera-bearing follower so devices don't
     * end up running two parallel sensor streams + per-frame main-thread posts.
     *
     * Subscribers run on whichever thread posted the bearing (the [HeadingSensor] in
     * [rememberGeoVaultMapUserLocationPlugin] dispatches on the main thread).
     */
    fun addBearingListener(listener: (Float) -> Unit) {
        bearingListeners += listener
    }

    fun removeBearingListener(listener: (Float) -> Unit) {
        bearingListeners -= listener
    }

    /**
     * Starts continuous GPS via the shared [GeoVaultMapGpsLocationEngine] (location FGS) and
     * feeds fixes into the MapLibre location renderer.
     *
     * While this session is active, updates continue when the host activity is backgrounded or
     * the screen is off. Caller must ensure location permissions are granted before calling.
     *
     * Common hosts should drive this through [com.geovault.common.maps.ui.location.GeoVaultMapLocationSessionPolicy]
     * + [com.geovault.common.maps.ui.lifecycle.GeoVaultMapUserLocationNavigationLifecycle].
     */
    @SuppressLint("MissingPermission")
    fun startRenderingGpsLocation(intervalMs: Long = 2000L) {
        stopRenderingGpsLocation()
        updatesSession = GeoVaultMapGpsLocationEngine.get(appContext).acquire(intervalMs) { location ->
            renderLocation(location)
        }
    }

    fun stopRenderingGpsLocation() {
        updatesSession?.stop()
        updatesSession = null
    }

    fun retryLocationForeground() {
        GeoVaultMapGpsLocationEngine.get(appContext).retryForegroundIfNeeded()
    }

    override fun onPluginDestroyed() {
        stopRenderingGpsLocation()
        locationListeners.clear()
        bearingListeners.clear()
        lastLocation = null
        puckBackgroundTranslucent = false
        map = null
    }

    @SuppressLint("MissingPermission")
    private fun bindToStyle(map: MapLibreMap, style: Style) {
        val locationComponent = map.locationComponent
        val componentActivated = locationComponent.isLocationComponentActivated
        if (!renderState.shouldBindStyle(componentActivated)) {
            renderState.applyTo(map)
            return
        }
        if (componentActivated) {
            LocationComponentHelper.applyStyle(map, appContext, effectiveConfig())
        } else {
            LocationComponentHelper.activate(map, style, appContext, effectiveConfig())
        }
        // Replace MapLibre's built-in 100 ms / no-animation `LocationComponentCompassEngine`
        // with our own engine. The puck's COMPASS-mode bearing now comes from the same
        // smoothed [HeadingSensor] stream the camera uses, via [updateBearing] →
        // [GeoVaultHeadingCompassEngine.pushHeading]. Idempotent: re-installing the same
        // engine instance is a no-op on MapLibre's side beyond a listener re-attach.
        locationComponent.compassEngine = headingCompassEngine
        renderState.applyTo(map)
        lastLocation?.let { LocationComponentHelper.forceLocation(map, it) }
        if (renderState.isEnabled && !style.hasLocationForegroundLayer()) {
            LocationComponentHelper.activate(map, style, appContext, effectiveConfig())
            map.locationComponent.compassEngine = headingCompassEngine
            renderState.applyTo(map)
            lastLocation?.let { LocationComponentHelper.forceLocation(map, it) }
        }
        renderState.markStyleBindingCurrent()
        logStyleBindResult(map, style)
    }

    private fun logStyleBindResult(map: MapLibreMap, style: Style) {
        val foregroundLayerPresent = style.hasLocationForegroundLayer()
        val message = "Location puck style bind complete. ${renderState.describe()} " +
            "component=${map.describeLocationComponent()} " +
            "foregroundLayerPresent=$foregroundLayerPresent"
        if (renderState.isEnabled && !foregroundLayerPresent) {
            Log.w(TAG, "$message; expected visible puck but MapLibre foreground layer is missing.")
        }
    }

    private fun Style.hasLocationForegroundLayer(): Boolean = getLayer(FOREGROUND_LAYER) != null

    private fun MapLibreMap.describeLocationComponent(): String {
        val locationComponent = locationComponent
        val activated = locationComponent.isLocationComponentActivated
        val enabled = if (activated) {
            runCatching { locationComponent.isLocationComponentEnabled }.getOrElse { error ->
                "error:${error.javaClass.simpleName}"
            }
        } else {
            "not-activated"
        }
        return "activated=$activated enabled=$enabled"
    }

    private fun effectiveConfig(): LocationComponentHelper.Config {
        val accuracyAlpha = if (accuracyCircleVisible) config.accuracyAlpha else 0f
        return config.copy(
            accuracyAlpha = accuracyAlpha,
            useTranslucentBackground = puckBackgroundTranslucent,
        )
    }

    private companion object {
        private const val TAG = "MapLocationRenderer"
    }
}
