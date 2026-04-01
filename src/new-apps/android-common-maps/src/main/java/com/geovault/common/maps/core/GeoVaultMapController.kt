package com.geovault.common.maps.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.geovault.common.maps.location.MapLocationRendererPlugin
import org.maplibre.android.camera.CameraUpdate
import org.maplibre.android.maps.MapLibreMap
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

class GeoVaultMapController(context: Context) : MapView.OnDidFailLoadingMapListener {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mapView: MapView? = null
    private var styleLoadWatchdog: Runnable? = null
    private var styleLoadGeneration: Long = 0L
    private var styleDeliveredForGeneration = false
    private val pluginRegistry = GeoVaultMapPluginRegistry()
    private val mapClickListeners = linkedSetOf<MapLibreMap.OnMapClickListener>()
    private val mapLongClickListeners = linkedSetOf<MapLibreMap.OnMapLongClickListener>()
    private val cameraMoveStartedListeners = linkedSetOf<MapLibreMap.OnCameraMoveStartedListener>()

    private val _phase = MutableStateFlow(GeoVaultMapPhase.Initializing)
    val phase: StateFlow<GeoVaultMapPhase> = _phase.asStateFlow()

    var onMapReady: ((MapLibreMap, Style) -> Unit)? = null
    var onStyleLoaded: ((MapLibreMap, Style) -> Unit)? = null
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

    fun attachMapView(view: MapView) {
        MapLibreInitializer.init(appContext)
        detachMapView()
        mapView = view
        _mapManager = MapLibreManager(appContext, view).also { manager ->
            manager.onStyleLoaded = { map, style ->
                styleDeliveredForGeneration = true
                clearStyleLoadWatchdog()
                _phase.value = GeoVaultMapPhase.Ready
                onStyleLoaded?.invoke(map, style)
                onMapReady?.invoke(map, style)
                pluginRegistry.forEach { it.onStyleLoaded(map, style) }
            }
            if (forceOsmOnly) {
                manager.sourceManager.setOsmOnly()
            }
        }
        view.addOnDidFailLoadingMapListener(this)
        view.getMapAsync { map ->
            maplibreMap = map
            mapClickListeners.forEach { map.addOnMapClickListener(it) }
            mapLongClickListeners.forEach { map.addOnMapLongClickListener(it) }
            cameraMoveStartedListeners.forEach { map.addOnCameraMoveStartedListener(it) }
            _phase.value = GeoVaultMapPhase.StyleLoading
            manager.setupBaseMapSettings(map)
            pluginRegistry.forEach { it.onMapReady(map) }
            styleLoadGeneration += 1L
            styleDeliveredForGeneration = false
            scheduleStyleLoadWatchdog(map, styleLoadGeneration)
            if (forceOsmOnly) {
                manager.applySelectedSource(map)
            } else {
                manager.fetchMapSources {
                    if (!manager.isCurrentSourceApplied(map)) {
                        manager.applySelectedSource(map)
                    }
                }
            }
        }
    }

    fun detachMapView() {
        clearStyleLoadWatchdog()
        mapView?.removeOnDidFailLoadingMapListener(this)
        mapView = null
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
            plugin.onMapReady(map)
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
        map.style?.let { style -> pluginRegistry.forEach { it.onStyleWillChange(map, style) } }
        _phase.value = GeoVaultMapPhase.StyleLoading
        manager.applySelectedSource(map)
    }

    fun applySourceSelection(optionId: String) {
        val map = maplibreMap ?: return
        val manager = _mapManager ?: return
        manager.sourceManager.setSelectedSourceId(optionId)
        map.style?.let { style -> pluginRegistry.forEach { it.onStyleWillChange(map, style) } }
        _phase.value = GeoVaultMapPhase.StyleLoading
        manager.applySelectedSource(map)
    }

    /**
     * Tiny convenience helper so apps can flip common location UI toggles through the controller.
     */
    fun setLocationPluginToggles(
        plugin: MapLocationRendererPlugin,
        showAccuracyCircle: Boolean? = null,
        trackingEnabled: Boolean? = null,
        locationEnabled: Boolean? = null,
    ) {
        showAccuracyCircle?.let { plugin.setAccuracyCircleVisible(it) }
        trackingEnabled?.let { plugin.setCameraTracking(it) }
        locationEnabled?.let { plugin.setEnabled(it) }
    }

    fun moveCameraWithPadding(update: CameraUpdate, padding: DoubleArray? = null) {
        val map = maplibreMap ?: return
        _mapManager?.moveCameraWithPadding(map, update, padding)
    }

    fun animateCameraWithPadding(
        update: CameraUpdate,
        padding: DoubleArray? = null,
        durationMs: Int = 300,
        callback: MapLibreMap.CancelableCallback? = null,
    ) {
        val map = maplibreMap ?: return
        _mapManager?.animateCameraWithPadding(map, update, padding, durationMs, callback)
    }

    fun addOnMapClickListener(listener: MapLibreMap.OnMapClickListener) {
        mapClickListeners.add(listener)
        maplibreMap?.addOnMapClickListener(listener)
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
    }

    override fun onDidFailLoadingMap(errorMessage: String) {
        val map = maplibreMap ?: return
        val manager = _mapManager ?: return
        Log.e(TAG, "Map style load failed: $errorMessage")
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
        const val TAG = "GeoVaultMapController"
    }
}
