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
    Error,
}

enum class GeoVaultMapErrorNoticeType {
    Configuration,
    StyleLoad,
}

data class GeoVaultMapErrorNotice(
    val type: GeoVaultMapErrorNoticeType,
    val title: String,
    val message: String,
    val retryable: Boolean = true,
)

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

    private val _mapAttachmentVersion = MutableStateFlow(0)
    val mapAttachmentVersion: StateFlow<Int> = _mapAttachmentVersion.asStateFlow()

    private val _errorNotice = MutableStateFlow<GeoVaultMapErrorNotice?>(null)
    val errorNotice: StateFlow<GeoVaultMapErrorNotice?> = _errorNotice.asStateFlow()

    var onMapReady: ((MapLibreMap, Style) -> Unit)? = null
    var onStyleLoaded: ((MapLibreMap, Style) -> Unit)? = null
    /** Fired for [MapView.OnDidFailLoadingMapListener] and recoverable style fetch failures from [MapLibreManager]. */
    var onStyleLoadFailed: ((errorMessage: String) -> Unit)? = null
    /** Fired when the GeoVault server reports incomplete map/font configuration. */
    var onMapConfigurationFailed: ((errorMessage: String) -> Unit)? = null
    /** Invoked after the style is loaded and all registered plugins have run [GeoVaultMapPlugin.onStyleLoaded]. */
    var onStyleReady: ((MapLibreMap, Style) -> Unit)? = null

    var maplibreMap: MapLibreMap? = null
        private set

    // Late map manager because it depends on the real map view.
    private var _mapManager: MapLibreManager? = null
    val manager: MapLibreManager
        get() = requireNotNull(_mapManager) { "Map manager unavailable until map view is attached." }

    init {
        registerPlugin(GeoVaultMapLongPressCopyCoordinatesPlugin())
    }

    internal fun attachMapView(view: MapView) {
        if (mapView === view && _mapManager != null) {
            return
        }
        MapLibreInitializer.init(appContext)
        detachMapView()
        mapView = view
        // Use the MapView/Activity context so [Configuration.UI_MODE_NIGHT_MASK] matches the
        // visible UI; [applicationContext] often keeps a stale/non-night configuration.
        val attachedManager = MapLibreManager(view.context, view).also { manager ->
            manager.defaultPadding = defaultCameraPadding
            manager.onStyleLoadFailed = { message ->
                if (_mapManager === manager && mapView === view) {
                    reportStyleLoadFailed(message)
                }
            }
            manager.onMapConfigurationFailed = { message ->
                if (_mapManager === manager && mapView === view) {
                    reportMapConfigurationFailed(message)
                }
            }
            manager.onStyleLoaded = { map, style ->
                // Ignore late style callbacks from stale attach cycles.
                if (_mapManager === manager && mapView === view) {
                    styleDeliveredForGeneration = true
                    clearStyleLoadWatchdog()
                    _errorNotice.value = null
                    onStyleLoaded?.invoke(map, style)
                    onMapReady?.invoke(map, style)
                    pluginRegistry.onStyleLoaded(map, style)
                    onStyleReady?.invoke(map, style)
                    _phase.value = GeoVaultMapPhase.Ready
                }
            }
        }
        _mapManager = attachedManager
        view.addOnDidFailLoadingMapListener(this)
        view.addOnDidFinishLoadingStyleListener(this)
        view.getMapAsync { map ->
            // getMapAsync can return after detach/re-attach; ignore stale callbacks.
            if (_mapManager !== attachedManager || mapView !== view) return@getMapAsync
            maplibreMap = map
            _mapAttachmentVersion.value += 1
            mapClickListeners.forEach { map.addOnMapClickListener(it) }
            mapLongClickListeners.forEach { map.addOnMapLongClickListener(it) }
            cameraMoveStartedListeners.forEach { map.addOnCameraMoveStartedListener(it) }
            _phase.value = GeoVaultMapPhase.StyleLoading
            attachedManager.setupBaseMapSettings(map)
            pluginRegistry.onMapAttached(map, view)
            attachedManager.fetchMapSources { canRenderMap ->
                if (_mapManager !== attachedManager || mapView !== view) return@fetchMapSources
                if (!canRenderMap) {
                    clearStyleLoadWatchdog()
                    _phase.value = GeoVaultMapPhase.Error
                    return@fetchMapSources
                }
                if (!attachedManager.isCurrentSourceApplied(map)) {
                    beginStyleLoad()
                    if (!attachedManager.applySelectedSource(map)) {
                        styleDeliveredForGeneration = true
                        clearStyleLoadWatchdog()
                        _phase.value = GeoVaultMapPhase.Ready
                    }
                } else {
                    styleDeliveredForGeneration = true
                    clearStyleLoadWatchdog()
                    _phase.value = GeoVaultMapPhase.Ready
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
        _mapAttachmentVersion.value += 1
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
        _mapManager?.fetchMapSources { onFetched() }
    }

    fun dismissMapErrorNotice() {
        _errorNotice.value = null
    }

    fun retryMapSourceLoad() {
        _errorNotice.value = null
        TileSourceCache.invalidate()
        MapStyleCache.invalidate()
        val map = maplibreMap ?: return
        val manager = _mapManager ?: return
        Log.i(TAG, "Retrying map source/style load.")
        _phase.value = GeoVaultMapPhase.StyleLoading
        manager.fetchMapSources { canRenderMap ->
            if (_mapManager !== manager || maplibreMap !== map) return@fetchMapSources
            if (!canRenderMap) {
                clearStyleLoadWatchdog()
                _phase.value = GeoVaultMapPhase.Error
                return@fetchMapSources
            }
            pluginRegistry.onStyleWillChange(map, map.style)
            beginStyleLoad()
            if (!manager.applySelectedSource(map)) {
                styleDeliveredForGeneration = true
                clearStyleLoadWatchdog()
                _phase.value = GeoVaultMapPhase.Ready
            }
        }
    }

    fun cycleSource() {
        val map = maplibreMap ?: return
        val manager = _mapManager ?: return
        manager.sourceManager.setSelectedSourceId(manager.sourceManager.getNextSourceId())
        pluginRegistry.onStyleWillChange(map, map.style)
        _phase.value = GeoVaultMapPhase.StyleLoading
        beginStyleLoad()
        if (!manager.applySelectedSource(map)) {
            styleDeliveredForGeneration = true
            clearStyleLoadWatchdog()
            _phase.value = GeoVaultMapPhase.Ready
        }
    }

    /**
     * Re-applies the current basemap without changing stored prefs, so the effective street source
     * can follow [android.content.res.Configuration.UI_MODE_NIGHT_MASK] (e.g. MapTiler dark vs light).
     * Intended for [GeoVaultMapHost] after sources are fetched and phase is [GeoVaultMapPhase.Ready].
     */
    fun reapplyBasemapAfterUiModeChange() {
        val map = maplibreMap ?: return
        val manager = _mapManager ?: return
        if (_phase.value != GeoVaultMapPhase.Ready || !manager.sourcesFetched) return
        pluginRegistry.onStyleWillChange(map, map.style)
        _phase.value = GeoVaultMapPhase.StyleLoading
        beginStyleLoad()
        if (!manager.applySelectedSource(map)) {
            styleDeliveredForGeneration = true
            clearStyleLoadWatchdog()
            _phase.value = GeoVaultMapPhase.Ready
        }
    }

    /**
     * Same `onResume` guard as the tracker map: if tile sources are known but the loaded style’s
     * key no longer matches [MapSourceManager.getEffectiveSourceId] (e.g. night mode changed while
     * paused), reload the basemap without changing prefs.
     */
    fun ensureBasemapMatchesEffectiveSelection() {
        val map = maplibreMap ?: return
        val manager = _mapManager ?: return
        if (_phase.value != GeoVaultMapPhase.Ready || !manager.sourcesFetched) return
        if (manager.isCurrentSourceApplied(map)) return
        pluginRegistry.onStyleWillChange(map, map.style)
        _phase.value = GeoVaultMapPhase.StyleLoading
        beginStyleLoad()
        if (!manager.applySelectedSource(map)) {
            styleDeliveredForGeneration = true
            clearStyleLoadWatchdog()
            _phase.value = GeoVaultMapPhase.Ready
        }
    }

    fun applySourceSelection(optionId: String) {
        val map = maplibreMap ?: return
        val manager = _mapManager ?: return
        manager.sourceManager.setSelectedSourceId(optionId)
        pluginRegistry.onStyleWillChange(map, map.style)
        _phase.value = GeoVaultMapPhase.StyleLoading
        beginStyleLoad()
        if (!manager.applySelectedSource(map)) {
            styleDeliveredForGeneration = true
            clearStyleLoadWatchdog()
            _phase.value = GeoVaultMapPhase.Ready
        }
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
        // Two-finger tilt is intentionally disabled across all geovault maps; users were tilting
        // the camera by accident during pinch zoom and the resulting pitch made the orthographic
        // overlays look broken. No app currently relies on a tilted camera.
        map.uiSettings.isTiltGesturesEnabled = false
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
        Log.e(TAG, "Map style load failed: $errorMessage")
        reportStyleLoadFailed(errorMessage)
        styleDeliveredForGeneration = true
        clearStyleLoadWatchdog()
        _phase.value = GeoVaultMapPhase.Error
    }

    private fun beginStyleLoad(): Long {
        styleLoadGeneration += 1L
        styleDeliveredForGeneration = false
        scheduleStyleLoadWatchdog(styleLoadGeneration)
        return styleLoadGeneration
    }

    private fun scheduleStyleLoadWatchdog(generation: Long) {
        clearStyleLoadWatchdog()
        styleLoadWatchdog = Runnable {
            if (generation != styleLoadGeneration) return@Runnable
            if (styleDeliveredForGeneration) return@Runnable
            Log.e(TAG, "Map style load timed out. generation=$generation")
            reportStyleLoadFailed("Map style load timed out.")
            styleDeliveredForGeneration = true
            clearStyleLoadWatchdog()
            _phase.value = GeoVaultMapPhase.Error
        }
        mainHandler.postDelayed(styleLoadWatchdog!!, STYLE_LOAD_TIMEOUT_MS)
    }

    private fun clearStyleLoadWatchdog() {
        styleLoadWatchdog?.let { mainHandler.removeCallbacks(it) }
        styleLoadWatchdog = null
    }

    private fun reportStyleLoadFailed(message: String) {
        Log.e(TAG, "Reporting map style load failure: $message")
        _errorNotice.value = GeoVaultMapErrorNotice(
            type = GeoVaultMapErrorNoticeType.StyleLoad,
            title = "Map Unavailable",
            message = "Map style failed to load: $message",
        )
        onStyleLoadFailed?.invoke(message)
    }

    private fun reportMapConfigurationFailed(message: String) {
        Log.e(TAG, "Reporting map configuration failure: $message")
        _errorNotice.value = GeoVaultMapErrorNotice(
            type = GeoVaultMapErrorNoticeType.Configuration,
            title = "Map Setup Required",
            message = message,
        )
        onMapConfigurationFailed?.invoke(message)
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
