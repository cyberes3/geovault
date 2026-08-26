package com.geovault.common.maps.core

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.common.net.GeoVaultValidatedInternetNotifier
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

data class GeoVaultMapDegradedNotice(
    val message: String,
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
    private var currentLoadHadDegradedFallback = false
    private var mapLibreForcedCacheOnly = false
    private var defaultCameraPadding: DoubleArray? = null
    private val pluginRegistry = GeoVaultMapPluginRegistry()
    private val mapClickListeners = linkedSetOf<MapLibreMap.OnMapClickListener>()
    private val mapLongClickListeners = linkedSetOf<MapLibreMap.OnMapLongClickListener>()
    private val cameraMoveStartedListeners = linkedSetOf<MapLibreMap.OnCameraMoveStartedListener>()
    private val networkRecoveryGate = MapNetworkRecoveryGate()
    private val networkRecoveryNotifier = GeoVaultValidatedInternetNotifier(appContext) {
        retryMapSourceLoadFromNetworkRecovery()
    }

    private val _phase = MutableStateFlow(GeoVaultMapPhase.Initializing)
    val phase: StateFlow<GeoVaultMapPhase> = _phase.asStateFlow()

    private val _mapAttachmentVersion = MutableStateFlow(0)
    val mapAttachmentVersion: StateFlow<Int> = _mapAttachmentVersion.asStateFlow()

    private val _errorNotice = MutableStateFlow<GeoVaultMapErrorNotice?>(null)
    val errorNotice: StateFlow<GeoVaultMapErrorNotice?> = _errorNotice.asStateFlow()

    private val _degradedNotice = MutableStateFlow<GeoVaultMapDegradedNotice?>(null)
    val degradedNotice: StateFlow<GeoVaultMapDegradedNotice?> = _degradedNotice.asStateFlow()

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
            manager.onMapDegraded = { message ->
                if (_mapManager === manager && mapView === view) {
                    reportMapDegraded(message)
                }
            }
            manager.onForcedCacheOnly = { cacheOnly ->
                if (_mapManager === manager && mapView === view) {
                    mapLibreForcedCacheOnly = cacheOnly
                    if (cacheOnly) {
                        updateNetworkRecoveryWatcher()
                    }
                }
            }
            manager.onStyleLoaded = { map, style ->
                // Ignore late style callbacks from stale attach cycles.
                if (_mapManager === manager && mapView === view) {
                    styleDeliveredForGeneration = true
                    clearStyleLoadWatchdog()
                    _errorNotice.value = null
                    completeMapLoad()
                    onStyleLoaded?.invoke(map, style)
                    onMapReady?.invoke(map, style)
                    pluginRegistry.onStyleLoaded(map, style)
                    onStyleReady?.invoke(map, style)
                    finishReadyPhase()
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
            beginNetworkSensitiveMapLoad()
            attachedManager.fetchMapSources { canRenderMap ->
                if (_mapManager !== attachedManager || mapView !== view) return@fetchMapSources
                val map = maplibreMap ?: return@fetchMapSources
                val currentSourceApplied = attachedManager.isCurrentSourceApplied(map)
                val alreadyReady = _phase.value == GeoVaultMapPhase.Ready
                GeoVaultCaptureLog.i(
                    TAG,
                    "map_sources_fetched canRenderMap=$canRenderMap currentSourceApplied=$currentSourceApplied " +
                        "alreadyReady=$alreadyReady mapClass=${this::class.simpleName}",
                )
                if (!canRenderMap) {
                    clearStyleLoadWatchdog()
                    _phase.value = GeoVaultMapPhase.Error
                    return@fetchMapSources
                }
                if (!currentSourceApplied) {
                    if (alreadyReady) {
                        pluginRegistry.onStyleWillChange(map, map.style)
                        currentLoadHadDegradedFallback = false
                    }
                    beginStyleLoad()
                    if (!attachedManager.applySelectedSource(map)) {
                        completeReadyWithoutStyleCallback()
                    }
                } else if (!alreadyReady) {
                    completeReadyWithoutStyleCallback()
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
        stopNetworkRecovery()
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

    fun dismissMapDegradedNotice() {
        _degradedNotice.value = null
    }

    fun retryMapSourceLoad() {
        _errorNotice.value = null
        mapLibreForcedCacheOnly = false
        MapLibreEngineConnectivity.apply(appContext, MapLibreConnectivityMode.FollowSystem)
        TileSourceCache.invalidate()
        MapStyleCache.invalidate()
        val map = maplibreMap ?: return
        val manager = _mapManager ?: return
        Log.i(TAG, "Retrying map source/style load.")
        _phase.value = GeoVaultMapPhase.StyleLoading
        beginNetworkSensitiveMapLoad()
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
                completeReadyWithoutStyleCallback()
            }
        }
    }

    private fun retryMapSourceLoadFromNetworkRecovery() {
        if (!shouldWatchNetworkForRecovery()) {
            stopNetworkRecovery()
            return
        }
        val shortCooldown = mapLibreForcedCacheOnly
        if (!networkRecoveryGate.shouldRetry(_phase.value, _errorNotice.value, preferShortCooldown = shortCooldown)) {
            Log.d(TAG, "Skipping map network recovery retry; phase=${_phase.value}")
            return
        }
        Log.i(TAG, "Retrying degraded map after validated internet became available.")
        GeoVaultCaptureLog.i(
            TAG,
            "map_retry_triggered reason=network_recovery phase=${_phase.value} " +
                "cacheOnly=$mapLibreForcedCacheOnly mapClass=${this::class.simpleName}",
        )
        MapLibreEngineConnectivity.apply(appContext, MapLibreConnectivityMode.FollowSystem)
        if (mapLibreForcedCacheOnly && _phase.value == GeoVaultMapPhase.Ready) {
            mapLibreForcedCacheOnly = false
            val map = maplibreMap ?: return
            val manager = _mapManager ?: return
            manager.fetchMapSources { canRenderMap ->
                if (_mapManager !== manager || maplibreMap !== map) return@fetchMapSources
                if (!canRenderMap) return@fetchMapSources
                if (!manager.isCurrentSourceApplied(map)) {
                    pluginRegistry.onStyleWillChange(map, map.style)
                    beginStyleLoad()
                    if (!manager.applySelectedSource(map)) {
                        completeReadyWithoutStyleCallback()
                    }
                }
            }
            return
        }
        mapLibreForcedCacheOnly = false
        retryMapSourceLoad()
    }

    fun cycleSource() {
        val map = maplibreMap ?: return
        val manager = _mapManager ?: return
        val previousSourceId = manager.sourceManager.getSelectedSourceId()
        val nextSourceId = manager.sourceManager.getNextSourceId()
        GeoVaultCaptureLog.i(
            TAG,
            "map_layer_toggle from=$previousSourceId to=$nextSourceId mapClass=${this::class.simpleName}",
        )
        manager.sourceManager.setSelectedSourceId(nextSourceId)
        pluginRegistry.onStyleWillChange(map, map.style)
        _phase.value = GeoVaultMapPhase.StyleLoading
        beginNetworkSensitiveMapLoad()
        beginStyleLoad()
        if (!manager.applySelectedSource(map)) {
            completeReadyWithoutStyleCallback()
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
        beginNetworkSensitiveMapLoad()
        beginStyleLoad()
        if (!manager.applySelectedSource(map)) {
            completeReadyWithoutStyleCallback()
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
        beginNetworkSensitiveMapLoad()
        beginStyleLoad()
        if (!manager.applySelectedSource(map)) {
            completeReadyWithoutStyleCallback()
        }
    }

    fun applySourceSelection(optionId: String) {
        val map = maplibreMap ?: return
        val manager = _mapManager ?: return
        manager.sourceManager.setSelectedSourceId(optionId)
        pluginRegistry.onStyleWillChange(map, map.style)
        _phase.value = GeoVaultMapPhase.StyleLoading
        beginNetworkSensitiveMapLoad()
        beginStyleLoad()
        if (!manager.applySelectedSource(map)) {
            completeReadyWithoutStyleCallback()
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
        stopNetworkRecovery()
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
        GeoVaultCaptureLog.e(TAG, "map_style_load_failed error=$errorMessage mapClass=${this::class.simpleName}")
        reportStyleLoadFailed(errorMessage)
        styleDeliveredForGeneration = true
        clearStyleLoadWatchdog()
        _phase.value = GeoVaultMapPhase.Error
        updateNetworkRecoveryWatcher()
    }

    private fun beginNetworkSensitiveMapLoad() {
        currentLoadHadDegradedFallback = false
        mapLibreForcedCacheOnly = false
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
            GeoVaultCaptureLog.e(
                TAG,
                "map_style_load_timeout generation=$generation timeoutMs=$STYLE_LOAD_TIMEOUT_MS " +
                    "mapClass=${this::class.simpleName}",
            )
            reportStyleLoadFailed("Map style load timed out.")
            styleDeliveredForGeneration = true
            clearStyleLoadWatchdog()
            _phase.value = GeoVaultMapPhase.Error
            updateNetworkRecoveryWatcher()
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
        updateNetworkRecoveryWatcher()
    }

    private fun reportMapConfigurationFailed(message: String) {
        Log.e(TAG, "Reporting map configuration failure: $message")
        _errorNotice.value = GeoVaultMapErrorNotice(
            type = GeoVaultMapErrorNoticeType.Configuration,
            title = "Map Setup Required",
            message = message,
        )
        onMapConfigurationFailed?.invoke(message)
        stopNetworkRecovery()
    }

    private fun reportMapDegraded(message: String) {
        Log.w(TAG, "Reporting degraded map availability: $message")
        GeoVaultCaptureLog.w(TAG, "map_degraded message=$message mapClass=${this::class.simpleName}")
        currentLoadHadDegradedFallback = true
        _degradedNotice.value = GeoVaultMapDegradedNotice(message = message)
        if (_phase.value != GeoVaultMapPhase.StyleLoading) {
            updateNetworkRecoveryWatcher()
        }
    }

    private fun completeReadyWithoutStyleCallback() {
        styleDeliveredForGeneration = true
        clearStyleLoadWatchdog()
        completeMapLoad()
        finishReadyPhase()
    }

    private fun completeMapLoad() {
        if (!currentLoadHadDegradedFallback && !mapLibreForcedCacheOnly) {
            _degradedNotice.value = null
            stopNetworkRecovery()
        } else {
            updateNetworkRecoveryWatcher()
        }
    }

    private fun finishReadyPhase() {
        _phase.value = GeoVaultMapPhase.Ready
        GeoVaultCaptureLog.i(
            TAG,
            "map_phase_ready degradedFallback=$currentLoadHadDegradedFallback mapClass=${this::class.simpleName}",
        )
        updateNetworkRecoveryWatcher()
    }

    private fun updateNetworkRecoveryWatcher() {
        if (shouldWatchNetworkForRecovery()) {
            networkRecoveryNotifier.start()
        } else {
            stopNetworkRecovery()
        }
    }

    private fun shouldWatchNetworkForRecovery(): Boolean {
        if (_phase.value == GeoVaultMapPhase.StyleLoading) return false
        val error = _errorNotice.value
        return mapLibreForcedCacheOnly ||
            _degradedNotice.value != null ||
            error?.type == GeoVaultMapErrorNoticeType.StyleLoad
    }

    private fun stopNetworkRecovery() {
        networkRecoveryNotifier.stop()
    }

    private companion object {
        const val STYLE_LOAD_TIMEOUT_MS = 30000L
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
