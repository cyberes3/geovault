package com.geovault.common.maps.core

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import org.maplibre.android.camera.CameraUpdate
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class GeoVaultMapPhase {
    Initializing,
    StyleLoading,
    Ready,
    Recovering,
}

/**
 * Root map abstraction that owns one active MapLibre session, plugin dispatch, source switching,
 * and camera APIs shared by all map modes.
 */
sealed class GeoVaultBaseMap(
    context: Context,
) : MapView.OnDidFailLoadingMapListener, MapView.OnDidFinishLoadingStyleListener {
    protected val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mapView: MapView? = null
    private var styleLoadWatchdog: Runnable? = null
    private var styleLoadGeneration: Long = 0L
    private var styleDeliveredForGeneration = false
    private var defaultCameraPadding: DoubleArray? = null
    private val pluginRegistry = GeoVaultMapPluginRegistry()
    private val mapClickListeners = linkedSetOf<MapLibreMap.OnMapClickListener>()
    private val mapLongClickListeners = linkedSetOf<MapLibreMap.OnMapLongClickListener>()
    private val cameraMoveStartedListeners = linkedSetOf<MapLibreMap.OnCameraMoveStartedListener>()

    private val _phase = MutableStateFlow(GeoVaultMapPhase.Initializing)
    val phase: StateFlow<GeoVaultMapPhase> = _phase.asStateFlow()

    var onMapReady: ((MapLibreMap, Style) -> Unit)? = null
    var onStyleLoaded: ((MapLibreMap, Style) -> Unit)? = null
    /** Fired for [MapView.OnDidFailLoadingMapListener] and recoverable style fetch failures from [MapLibreManager]. */
    var onStyleLoadFailed: ((errorMessage: String) -> Unit)? = null
    /** Invoked after the style is loaded and all registered plugins have run [GeoVaultMapPlugin.onStyleLoaded]. */
    var onStyleReady: ((MapLibreMap, Style) -> Unit)? = null
    var forceOsmOnly: Boolean = false
        set(value) {
            field = value
            if (value) {
                _mapManager?.sourceManager?.setOsmOnly()
            }
        }

    var maplibreMap: MapLibreMap? = null
        private set

    // Late map manager because it depends on the real map view.
    private var _mapManager: MapLibreManager? = null
    val manager: MapLibreManager
        get() = requireNotNull(_mapManager) { "Map manager unavailable until map view is attached." }

    internal fun attachMapView(view: MapView) {
        if (mapView === view && _mapManager != null) {
            return
        }
        MapLibreInitializer.init(appContext)
        detachMapView()
        mapView = view
        val attachedManager = MapLibreManager(appContext, view).also { manager ->
            manager.defaultPadding = defaultCameraPadding
            manager.onStyleLoadFailed = { message ->
                if (_mapManager === manager && mapView === view) {
                    onStyleLoadFailed?.invoke(message)
                }
            }
            manager.onStyleLoaded = { map, style ->
                // Ignore late style callbacks from stale attach cycles.
                if (_mapManager === manager && mapView === view) {
                    styleDeliveredForGeneration = true
                    clearStyleLoadWatchdog()
                    _phase.value = GeoVaultMapPhase.Ready
                    onStyleLoaded?.invoke(map, style)
                    onMapReady?.invoke(map, style)
                    pluginRegistry.onStyleLoaded(map, style)
                    onStyleReady?.invoke(map, style)
                }
            }
            if (forceOsmOnly) {
                manager.sourceManager.setOsmOnly()
            }
        }
        _mapManager = attachedManager
        view.addOnDidFailLoadingMapListener(this)
        view.addOnDidFinishLoadingStyleListener(this)
        view.getMapAsync { map ->
            // getMapAsync can return after detach/re-attach; ignore stale callbacks.
            if (_mapManager !== attachedManager || mapView !== view) return@getMapAsync
            maplibreMap = map
            mapClickListeners.forEach { map.addOnMapClickListener(it) }
            mapLongClickListeners.forEach { map.addOnMapLongClickListener(it) }
            cameraMoveStartedListeners.forEach { map.addOnCameraMoveStartedListener(it) }
            _phase.value = GeoVaultMapPhase.StyleLoading
            attachedManager.setupBaseMapSettings(map)
            pluginRegistry.onMapAttached(map)
            styleLoadGeneration += 1L
            styleDeliveredForGeneration = false
            scheduleStyleLoadWatchdog(map, styleLoadGeneration)
            if (forceOsmOnly) {
                attachedManager.applySelectedSource(map)
            } else {
                attachedManager.fetchMapSources {
                    if (_mapManager !== attachedManager || mapView !== view) return@fetchMapSources
                    if (!attachedManager.isCurrentSourceApplied(map)) {
                        attachedManager.applySelectedSource(map)
                    }
                }
            }
        }
    }

    protected fun createMapView(): MapView {
        val options = MapLibreMapOptions.createFromAttributes(appContext).apply {
            textureMode(true)
            compassEnabled(false)
            minZoomPreference(MapLibreManager.MIN_ZOOM_LEVEL.toDouble())
            maxZoomPreference(MapLibreManager.MAX_ZOOM_LEVEL.toDouble())
        }
        return MapView(appContext, options)
    }

    internal fun detachMapView() {
        clearStyleLoadWatchdog()
        mapView?.removeOnDidFailLoadingMapListener(this)
        mapView?.removeOnDidFinishLoadingStyleListener(this)
        mapView = null
        pluginRegistry.onMapDetached()
        maplibreMap?.let { map ->
            mapClickListeners.forEach { map.removeOnMapClickListener(it) }
            mapLongClickListeners.forEach { map.removeOnMapLongClickListener(it) }
            cameraMoveStartedListeners.forEach { map.removeOnCameraMoveStartedListener(it) }
        }
        maplibreMap = null
        _mapManager = null
    }

    fun registerPlugin(plugin: GeoVaultMapPlugin) {
        pluginRegistry.add(plugin)
        maplibreMap?.let { map ->
            map.style?.let { style -> plugin.onStyleLoaded(map, style) }
        }
    }

    fun unregisterPlugin(plugin: GeoVaultMapPlugin) {
        pluginRegistry.remove(plugin)
    }

    fun fetchSources(onFetched: () -> Unit = {}) {
        _mapManager?.fetchMapSources(onFetched)
    }

    fun cycleSource() {
        val map = maplibreMap ?: return
        val manager = _mapManager ?: return
        manager.sourceManager.setSelectedSourceId(manager.sourceManager.getNextSourceId())
        pluginRegistry.onStyleWillChange(map, map.style)
        _phase.value = GeoVaultMapPhase.StyleLoading
        manager.applySelectedSource(map)
    }

    fun applySourceSelection(optionId: String) {
        val map = maplibreMap ?: return
        val manager = _mapManager ?: return
        manager.sourceManager.setSelectedSourceId(optionId)
        pluginRegistry.onStyleWillChange(map, map.style)
        _phase.value = GeoVaultMapPhase.StyleLoading
        manager.applySelectedSource(map)
    }

    fun moveCameraWithPadding(
        update: CameraUpdate,
        padding: DoubleArray? = null,
        maxZoom: Double? = null,
    ) {
        val map = maplibreMap ?: return
        val mgr = _mapManager ?: return
        mgr.moveCameraWithPadding(map, update, padding, maxZoom = maxZoom ?: mgr.resolveEffectiveMaxZoom())
    }

    fun animateCameraWithPadding(
        update: CameraUpdate,
        padding: DoubleArray? = null,
        durationMs: Int = 300,
        callback: MapLibreMap.CancelableCallback? = null,
        maxZoom: Double? = null,
    ) {
        val map = maplibreMap ?: return
        val mgr = _mapManager ?: return
        mgr.animateCameraWithPadding(map, update, padding, durationMs, callback, maxZoom = maxZoom ?: mgr.resolveEffectiveMaxZoom())
    }

    /**
     * Re-applies expected gesture settings for the retained map instance.
     * This is a defensive no-op when the map is not ready.
     */
    fun ensureInteractiveGestures() {
        val map = maplibreMap ?: return
        map.uiSettings.setCompassEnabled(false)
        map.uiSettings.isScrollGesturesEnabled = true
        map.uiSettings.isZoomGesturesEnabled = true
        map.uiSettings.isTiltGesturesEnabled = true
        map.uiSettings.isDoubleTapGesturesEnabled = true
        map.uiSettings.isRotateGesturesEnabled = false
    }

    fun addOnMapClickListener(listener: MapLibreMap.OnMapClickListener) {
        mapClickListeners.add(listener)
        maplibreMap?.addOnMapClickListener(listener)
    }

    /**
     * Sets default camera padding used when callers do not provide explicit padding.
     * Format is [left, top, right, bottom] in pixels.
     */
    fun setDefaultCameraPadding(padding: DoubleArray?) {
        defaultCameraPadding = padding
        _mapManager?.defaultPadding = padding
    }

    fun removeOnMapClickListener(listener: MapLibreMap.OnMapClickListener) {
        mapClickListeners.remove(listener)
        maplibreMap?.removeOnMapClickListener(listener)
    }

    fun addOnMapLongClickListener(listener: MapLibreMap.OnMapLongClickListener) {
        mapLongClickListeners.add(listener)
        maplibreMap?.addOnMapLongClickListener(listener)
    }

    fun removeOnMapLongClickListener(listener: MapLibreMap.OnMapLongClickListener) {
        mapLongClickListeners.remove(listener)
        maplibreMap?.removeOnMapLongClickListener(listener)
    }

    fun addOnCameraMoveStartedListener(listener: MapLibreMap.OnCameraMoveStartedListener) {
        cameraMoveStartedListeners.add(listener)
        maplibreMap?.addOnCameraMoveStartedListener(listener)
    }

    fun removeOnCameraMoveStartedListener(listener: MapLibreMap.OnCameraMoveStartedListener) {
        cameraMoveStartedListeners.remove(listener)
        maplibreMap?.removeOnCameraMoveStartedListener(listener)
    }

    fun getMapViewOrNull(): MapView? = mapView

    fun onDestroy() {
        pluginRegistry.clearAndDestroy()
        mapClickListeners.clear()
        mapLongClickListeners.clear()
        cameraMoveStartedListeners.clear()
        clearStyleLoadWatchdog()
        detachMapView()
        onMapDestroyed()
    }

    internal abstract fun acquireMapView(stateBundle: Bundle): MapView
    protected abstract fun onMapDestroyed()

    override fun onDidFinishLoadingStyle() {
        val map = maplibreMap ?: return
        _mapManager?.reapplyZoomPreferences(map)
    }

    override fun onDidFailLoadingMap(errorMessage: String) {
        val map = maplibreMap ?: return
        val manager = _mapManager ?: return
        Log.e(TAG, "Map style load failed: $errorMessage")
        onStyleLoadFailed?.invoke(errorMessage)
        styleDeliveredForGeneration = true
        clearStyleLoadWatchdog()
        _phase.value = GeoVaultMapPhase.Recovering
        val effectiveId = manager.sourceManager.getEffectiveSourceId()
        if (manager.sourceManager.isVectorSource(effectiveId)) {
            manager.loadOsmFallback(map)
        }
    }

    private fun scheduleStyleLoadWatchdog(map: MapLibreMap, generation: Long) {
        clearStyleLoadWatchdog()
        styleLoadWatchdog = Runnable {
            if (generation != styleLoadGeneration) return@Runnable
            if (styleDeliveredForGeneration) return@Runnable
            _phase.value = GeoVaultMapPhase.Recovering
            _mapManager?.loadOsmFallback(map)
        }
        mainHandler.postDelayed(styleLoadWatchdog!!, STYLE_LOAD_TIMEOUT_MS)
    }

    private fun clearStyleLoadWatchdog() {
        styleLoadWatchdog?.let { mainHandler.removeCallbacks(it) }
        styleLoadWatchdog = null
    }

    private companion object {
        const val STYLE_LOAD_TIMEOUT_MS = 5000L
        const val TAG = "GeoVaultBaseMap"
    }
}

class GeoVaultStandardMap(
    context: Context,
) : GeoVaultBaseMap(context) {
    override fun acquireMapView(stateBundle: Bundle): MapView {
        return createMapView().also { created ->
            created.onCreate(stateBundle)
        }
    }

    override fun onMapDestroyed() = Unit
}

class GeoVaultMainMap(
    context: Context,
) : GeoVaultBaseMap(context) {
    private var retainedMapView: MapView? = null

    override fun acquireMapView(stateBundle: Bundle): MapView {
        val existing = retainedMapView
        if (existing != null) {
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }
        return createMapView().also { created ->
            created.onCreate(stateBundle)
            retainedMapView = created
        }
    }

    /**
     * Eagerly create + attach the retained [MapView] so style/tile loading starts before any
     * Compose surface mounts it.
     *
     * The warmup is posted to the main thread from a background task, so on a normal cold
     * start it commonly races with the first Compose mount of [GeoVaultMainMapView]. If
     * Compose has already mounted the retained `MapView` into its host `ViewGroup`,
     * [acquireMapView] would yank it out of that parent — leaving the `TextureView`
     * detached from the window with no `SurfaceTexture`, so the map renders blank until
     * something forces a route remount (e.g. opening a file map). Detect that case via
     * [getMapViewOrNull] (set by `attachMapView`) and skip — Compose has already done the
     * equivalent work for us.
     */
    fun preload() {
        if (getMapViewOrNull() != null) return
        val view = acquireMapView(Bundle())
        attachMapView(view)
        view.onStart()
        view.onResume()
    }

    override fun onMapDestroyed() {
        retainedMapView?.onDestroy()
        retainedMapView = null
    }
}
