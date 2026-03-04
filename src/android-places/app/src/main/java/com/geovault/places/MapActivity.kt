package com.geovault.places

import android.content.Intent
import com.geovault.common.GeovaultAuthManager
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import android.view.ViewTreeObserver
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.content.ContextCompat
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.plugins.annotation.Symbol
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.android.plugins.annotation.SymbolOptions
import android.util.Log
import android.widget.Toast
import java.util.ArrayList
import java.util.concurrent.Executors

class MapActivity : AppCompatActivity(), OnMapReadyCallback, MapView.OnDidFailLoadingMapListener {

    private lateinit var mapView: MapView
    private var maplibreMap: MapLibreMap? = null
    private lateinit var features: ArrayList<Feature>
    private var symbolManager: SymbolManager? = null
    /** Selection overlay: single yellow symbol on a layer above all blue markers. */
    private var selectionSymbolManager: SymbolManager? = null
    private var selectionSymbol: Symbol? = null
    private var lastSelectedSymbol: Symbol? = null
    private val symbols = mutableListOf<Symbol>()
    private val symbolToFeature = mutableMapOf<Symbol, Feature>()
    private var selectedFeature: Feature? = null
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var sourceManager: MapSourceManager
    /** True when map is ready (onMapReady ran). */
    private var mapReady = false
    /** True when fetchMapSources callback ran (server configured). */
    private var sourcesFetched = false
    /** True after we've applied initial fit-bounds/zoom once; when restored we skip so MapView saved state keeps camera. */
    private var initialCameraApplied = false

    private val cache: PlacesCache
        get() = (application as PlacesApplication).placesCache

    private val editLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data
        when {
            data?.getParcelableExtra("offline_feature", Feature::class.java) != null -> {
                val feature = data.getParcelableExtra("offline_feature", Feature::class.java)!!
                val original = data.getParcelableExtra("original_feature", Feature::class.java)
                val offlineEditIndex = data.getIntExtra("offline_edit_index", -1)
                cache.addOrUpdateOffline(feature, original, offlineEditIndex)
                loadFeaturesFromCache()
            }
            data?.getParcelableExtra("updated_feature", Feature::class.java) != null -> {
                val updated = data.getParcelableExtra("updated_feature", Feature::class.java)!!
                cache.updateCachedFeature(updated)
                loadFeaturesFromCache()
            }
            else -> loadFeaturesFromCache()
        }
        setResult(RESULT_OK, data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sourceManager = MapSourceManager(this)
        setContentView(R.layout.activity_map)
        // Apply window insets in onCreate so we get initial dispatch (insets are sent when window attaches;
        // registering in onMapReady was too late and nav bar covered content).
        val rootView = findViewById<View>(R.id.rootLayout)
        val headerView = findViewById<View>(R.id.headerLayout)
        val bottomInfoLayout = findViewById<View>(R.id.bottomInfoLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            headerView.updatePadding(top = insets.top + 20)
            bottomInfoLayout.updatePadding(bottom = insets.bottom + 16)
            WindowInsetsCompat.CONSUMED
        }
        mapView = findViewById(R.id.map)
        // MapLibre shows a foreground drawable until the map loads; use theme-aware color (black in dark mode).
        mapView.foreground = android.graphics.drawable.ColorDrawable(ContextCompat.getColor(this, R.color.map_underlay))
        mapView.addOnDidFailLoadingMapListener(this)
        mapView.onCreate(savedInstanceState)
        initialCameraApplied = savedInstanceState?.getBoolean(KEY_INITIAL_CAMERA_APPLIED, false) ?: false
        features = ArrayList(cache.getDisplayFeatures())
        mapView.getMapAsync(this)
        updateMapAuthHeader()
        fetchMapSources()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                startActivity(Intent(this@MapActivity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                })
                safeNoAnimation()
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadFeaturesFromCache()
        applyZoomFromIntent()
    }

    /** Apply zoom_to_lat, zoom_to_lon, zoom_to_id from current intent (e.g. when opened from list "view on map"). */
    private fun applyZoomFromIntent() {
        if (!intent.hasExtra("zoom_to_lat") || !intent.hasExtra("zoom_to_lon")) return
        val map = maplibreMap ?: return
        val centerLat = intent.getDoubleExtra("zoom_to_lat", 0.0)
        val centerLon = intent.getDoubleExtra("zoom_to_lon", 0.0)
        val zoomToId = intent.getIntExtra("zoom_to_id", -1)
        map.setCameraPosition(CameraPosition.Builder().target(LatLng(centerLat, centerLon)).zoom(DEFAULT_POINT_ZOOM).build())
        if (zoomToId >= 0) {
            val pair = symbolToFeature.entries.find { it.value.properties.database_id == zoomToId }
            pair?.let { (symbol, feature) -> selectMarkerAndUpdateUi(symbol, feature) }
        }
    }

    /** Single refresh path: load features from cache and redraw markers. */
    private fun loadFeaturesFromCache() {
        features.clear()
        features.addAll(cache.getDisplayFeatures())
        refreshMarkers()
    }

    /** Clear existing markers and redraw from current {@code features} list. Call after updating features. */
    private fun refreshMarkers() {
        val map = maplibreMap ?: return
        if (map.style == null) return
        selectionSymbol?.let { selectionSymbolManager?.delete(it) }
        selectionSymbol = null
        lastSelectedSymbol = null
        selectedFeature = null
        selectionSymbolManager?.onDestroy()
        selectionSymbolManager = null
        symbolManager?.onDestroy()
        symbolManager = null
        symbols.clear()
        symbolToFeature.clear()
        clearSelectionUi()
        addMarkersIfReady(map)
    }

    override fun onDidFailLoadingMap(errorMessage: String) {
        Log.e(TAG, "Map style load failed: $errorMessage")
        runOnUiThread {
            val map = maplibreMap ?: return@runOnUiThread
            val effectiveId = sourceManager.getEffectiveSourceId()
            if (sourceManager.isVectorSource(effectiveId)) {
                Toast.makeText(this, getString(R.string.map_style_unavailable_fallback_osm), Toast.LENGTH_SHORT).show()
                loadOsmFallback(map)
            } else {
                Toast.makeText(this, "Map failed: $errorMessage", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun fetchMapSources() {
        val serverUrl = GeovaultAuthManager.getServerUrl(this)
        if (serverUrl.isEmpty()) return
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = com.geovault.common.RetrofitClient.getClient(this, baseUrl).create(GeovaultApi::class.java)
        sourceManager.fetchSources(api) {
            runOnUiThread {
                if (!isDestroyed) {
                    sourcesFetched = true
                    if (mapReady) applySelectedSource()
                }
            }
        }
    }

    override fun onMapReady(map: MapLibreMap) {
        maplibreMap = map
        mapReady = true
        map.uiSettings.setLogoEnabled(false)
        map.uiSettings.setAttributionEnabled(false)
        map.uiSettings.isRotateGesturesEnabled = false
        map.setMaxZoomPreference(MAX_ZOOM_LEVEL.toDouble())
        setupMapUi()
        // Load style once: when no server load now (OSM); when server configured wait for fetchSources callback.
        if (sourcesFetched || GeovaultAuthManager.getServerUrl(this).isEmpty()) {
            applySelectedSource()
        }
    }

    private fun applySelectedSource() {
        val map = maplibreMap ?: run {
            Log.d(TAG, "applySelectedSource: map not ready yet, skipping")
            return
        }
        if (isDestroyed) return
        Log.d(TAG, "applySelectedSource: effectiveId=${sourceManager.getEffectiveSourceId()}")
        // Remove yellow marker before destroying manager so it doesn't linger; clear selection state
        selectionSymbol?.let { sym -> selectionSymbolManager?.delete(sym) }
        selectionSymbol = null
        selectionSymbolManager?.onDestroy()
        selectionSymbolManager = null
        symbolManager?.onDestroy()
        symbolManager = null
        symbols.clear()
        symbolToFeature.clear()
        lastSelectedSymbol = null
        selectedFeature = null
        clearSelectionUi()
        try {
            val effectiveId = sourceManager.getEffectiveSourceId()
            val mapMaxZoom = if (sourceManager.isVectorSource(effectiveId)) MAX_ZOOM_LEVEL_VECTOR.toDouble() else MAX_ZOOM_LEVEL.toDouble()
            map.setMaxZoomPreference(mapMaxZoom)
            if (sourceManager.isVectorSource(effectiveId)) {
                val styleUrl = sourceManager.getResolvedStyleUrl(effectiveId)
                if (!styleUrl.isNullOrBlank()) {
                    loadVectorStyle(map, styleUrl)
                } else {
                    map.setStyle(Style.Builder()) { if (!isDestroyed) applyStyleLoaded(map) }
                }
            } else {
                val rasterUrl = sourceManager.getRasterUrl(effectiveId)
                if (!rasterUrl.isNullOrBlank()) {
                    map.setStyle(Style.Builder()) { style ->
                        if (isDestroyed) return@setStyle
                        try {
                            // tileSize 256, maxZoom capped at 15; tile layer below annotations.
                            val tileSet = TileSet("2.1.0", rasterUrl).apply {
                                maxZoom = MAX_ZOOM_LEVEL.toFloat()
                            }
                            style.addSource(RasterSource(RASTER_SOURCE_ID, tileSet, 256))
                            val rasterLayer = RasterLayer(RASTER_LAYER_ID, RASTER_SOURCE_ID)
                            try {
                                style.addLayerBelow(rasterLayer, ANNOTATIONS_LAYER_ID)
                            } catch (_: Exception) {
                                style.addLayer(rasterLayer)
                            }
                        } catch (_: Exception) { /* ignore */ }
                        if (!isDestroyed) applyStyleLoaded(map)
                    }
                } else {
                    map.setStyle(Style.Builder()) { if (!isDestroyed) applyStyleLoaded(map) }
                }
            }
        } catch (_: Exception) {
            map.setStyle(Style.Builder()) { if (!isDestroyed) applyStyleLoaded(map) }
        }
    }

    /**
     * Load vector style from URL. Uses MapStyleCache so repeat layer switches load instantly.
     * On failure, falls back to OSM raster.
     */
    private fun loadVectorStyle(map: MapLibreMap, styleUrl: String) {
        Log.d(TAG, "loadVectorStyle: $styleUrl")
        val serverUrl = GeovaultAuthManager.getServerUrl(this).trimEnd('/')
        val isOurServer = serverUrl.isNotEmpty() && (styleUrl == serverUrl || styleUrl.startsWith("$serverUrl/"))
        val serverBase = if (isOurServer) java.net.URI.create(styleUrl).let { "${it.scheme}://${it.host}" } else null

        MapStyleCache.getStyleJson(this, styleUrl, isOurServer, serverBase) { json ->
            if (isDestroyed) return@getStyleJson
            if (!json.isNullOrBlank()) {
                map.setStyle(Style.Builder().fromJson(json)) {
                    Log.d(TAG, "loadVectorStyle: style loaded (fromJson)")
                    if (!isDestroyed) applyStyleLoaded(map)
                }
            } else {
                Toast.makeText(this@MapActivity, getString(R.string.map_style_unavailable_fallback_osm), Toast.LENGTH_SHORT).show()
                loadOsmFallback(map)
            }
        }
    }

    /** Load OSM raster as fallback when vector (MapTiler) street style fails. */
    private fun loadOsmFallback(map: MapLibreMap) {
        val rasterUrl = sourceManager.getStreetFallbackRasterUrl()
        if (rasterUrl.isNullOrBlank()) {
            Log.d(TAG, "loadOsmFallback: no raster URL, empty style")
            map.setStyle(Style.Builder()) { if (!isDestroyed) applyStyleLoaded(map) }
            return
        }
        Log.d(TAG, "loadOsmFallback: start, rasterUrl=$rasterUrl")
        map.setMaxZoomPreference(MAX_ZOOM_LEVEL.toDouble())
        map.setStyle(Style.Builder()) { style ->
            if (isDestroyed) return@setStyle
            try {
                style.addSource(RasterSource(RASTER_SOURCE_ID, TileSet("2.1.0", rasterUrl).apply { maxZoom = MAX_ZOOM_LEVEL.toFloat() }, 256))
                val rasterLayer = RasterLayer(RASTER_LAYER_ID, RASTER_SOURCE_ID)
                Log.d(TAG, "loadOsmFallback: added raster source, calling applyStyleLoaded")
                if (!isDestroyed) applyStyleLoaded(map)
                val mgr = symbolManager
                Log.d(TAG, "loadOsmFallback: after applyStyleLoaded markersLayerId=${mgr?.layerId} selectionLayerId=${selectionSymbolManager?.layerId}")
                logStyleLayerOrder(style, "loadOsmFallback after symbols")
                mapView.post {
                    if (isDestroyed) return@post
                    val s = map.style ?: run {
                        Log.e(TAG, "loadOsmFallback post: style is null")
                        return@post
                    }
                    try {
                        if (mgr != null) {
                            s.addLayerBelow(rasterLayer, mgr.layerId)
                            Log.d(TAG, "loadOsmFallback post: added raster below layer ${mgr.layerId}")
                        } else {
                            s.addLayer(rasterLayer)
                            Log.d(TAG, "loadOsmFallback post: symbolManager null, added raster with addLayer")
                        }
                        logStyleLayerOrder(s, "loadOsmFallback after raster")
                    } catch (e: Exception) {
                        Log.e(TAG, "loadOsmFallback post: addLayerBelow failed", e)
                        try {
                            s.addLayer(rasterLayer)
                            Log.d(TAG, "loadOsmFallback post: fallback addLayer(raster) ok")
                        } catch (e2: Exception) {
                            Log.e(TAG, "loadOsmFallback post: addLayer(raster) also failed", e2)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadOsmFallback: exception", e)
            }
        }
    }

    private fun logStyleLayerOrder(style: Style, label: String) {
        try {
            val ids = style.layers.map { it.id }
            Log.d(TAG, "layerOrder [$label]: ${ids.joinToString(" -> ")}")
        } catch (e: Exception) {
            Log.e(TAG, "layerOrder [$label]: failed to get layers", e)
        }
    }

    /** Move selection layer to top of draw order so yellow marker is not covered by other symbol layers. */
    private fun moveSelectionLayerToTop() {
        val style = maplibreMap?.style ?: return
        val selManager = selectionSymbolManager ?: return
        val layerId = selManager.layerId
        try {
            val layer = style.getLayer(layerId) ?: return
            if (style.removeLayer(layer)) {
                style.addLayer(layer)
                Log.d(TAG, "moveSelectionLayerToTop: moved $layerId to top")
            }
        } catch (e: Exception) {
            Log.e(TAG, "moveSelectionLayerToTop: failed", e)
        }
    }

    private fun applyStyleLoaded(map: MapLibreMap) {
        Log.d(TAG, "applyStyleLoaded: adding markers")
        addMarkersIfReady(map)
    }

    private fun setupMapUi() {
        findViewById<View>(R.id.settingsButton).setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
            safeNoAnimation()
        }
        findViewById<View>(R.id.mapToggle).setOnClickListener {
            val nextSourceId = sourceManager.getNextSourceId()
            sourceManager.setSelectedSourceId(nextSourceId)
            applySelectedSource()
        }
        val placeName = findViewById<android.widget.TextView>(R.id.placeName)
        val placeDescription = findViewById<android.widget.TextView>(R.id.placeDescription)
        val viewInListButton = findViewById<android.widget.Button>(R.id.viewInListButton)
        val editPlaceButton = findViewById<android.widget.Button>(R.id.editPlaceButton)
        val navigateButton = findViewById<android.widget.Button>(R.id.navigateButton)
        editPlaceButton.setOnClickListener {
            selectedFeature?.let { feature ->
                val intent = android.content.Intent(this, PlaceEditActivity::class.java)
                intent.putExtra("feature", feature)
                editLauncher.launch(intent)
                safeNoAnimation()
            }
        }
        navigateButton.setOnClickListener {
            selectedFeature?.let { feature ->
                val serverUrl = GeovaultAuthManager.getServerUrl(this)
                NavigationHelper.navigateToPlace(this, feature, serverUrl)
            }
        }
        viewInListButton.setOnClickListener {
            selectedFeature?.properties?.database_id?.let { id ->
                startActivity(Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    putExtra(MainActivity.EXTRA_SELECTED_ID_FROM_MAP, id)
                })
                safeNoAnimation()
            }
        }
    }

    private fun selectMarkerAndUpdateUi(symbol: Symbol, f: Feature) {
        Log.d(TAG, "selectMarkerAndUpdateUi: latLng=${symbol.latLng}")
        lastSelectedSymbol = symbol
        selectedFeature = f
        val selManager = selectionSymbolManager
        if (selManager == null) {
            Log.e(TAG, "selectMarkerAndUpdateUi: selectionSymbolManager is null, cannot show yellow marker")
            return
        }
        selectionSymbol?.let { selManager.delete(it) }
        selectionSymbol = selManager.create(
            SymbolOptions()
                .withLatLng(symbol.latLng)
                .withIconImage(ICON_MARKER_SELECTED)
        )
        Log.d(TAG, "selectMarkerAndUpdateUi: created selection symbol on layer ${selManager.layerId}")
        moveSelectionLayerToTop()
        maplibreMap?.style?.let { logStyleLayerOrder(it, "after selectMarker") }

        findViewById<android.widget.TextView>(R.id.placeName).text = f.properties.name ?: "Unknown Place"
        findViewById<android.widget.TextView>(R.id.placeDescription).text = f.properties.description ?: ""
        val dbId = f.properties.database_id
        val viewInListButton = findViewById<android.widget.Button>(R.id.viewInListButton)
        val editPlaceButton = findViewById<android.widget.Button>(R.id.editPlaceButton)
        val navigateButton = findViewById<android.widget.Button>(R.id.navigateButton)
        viewInListButton.isEnabled = dbId != null
        viewInListButton.alpha = if (dbId != null) 1.0f else 0.5f
        editPlaceButton.isEnabled = dbId != null
        editPlaceButton.alpha = if (dbId != null) 1.0f else 0.5f
        navigateButton.isEnabled = dbId != null
        navigateButton.alpha = if (dbId != null) 1.0f else 0.5f
    }

    /** Clear selection panel when switching map layers or leaving map; show default prompt. */
    private fun clearSelectionUi() {
        findViewById<android.widget.TextView>(R.id.placeName).text = getString(R.string.map_select_place)
        findViewById<android.widget.TextView>(R.id.placeDescription).text = getString(R.string.map_tap_marker_hint)
        val viewInListButton = findViewById<android.widget.Button>(R.id.viewInListButton)
        val editPlaceButton = findViewById<android.widget.Button>(R.id.editPlaceButton)
        val navigateButton = findViewById<android.widget.Button>(R.id.navigateButton)
        viewInListButton.isEnabled = false
        viewInListButton.alpha = 0.5f
        editPlaceButton.isEnabled = false
        editPlaceButton.alpha = 0.5f
        navigateButton.isEnabled = false
        navigateButton.alpha = 0.5f
    }

    private fun addMarkersIfReady(map: MapLibreMap?) {
        if (isDestroyed) return
        val mapRef = map ?: maplibreMap ?: return
        if (features.isEmpty()) {
            Log.d(TAG, "addMarkersIfReady: no features, skip")
            return
        }
        val style = mapRef.style ?: return
        Log.d(TAG, "addMarkersIfReady: style loaded, features=${features.size}")
        symbols.clear()
        symbolToFeature.clear()
        lastSelectedSymbol = null
        selectionSymbol = null
        symbolManager = null
        selectionSymbolManager = null
        val defaultBitmap = MapMarkerUtils.getMarkerBitmap(this, R.drawable.ic_marker_default)
        val selectedBitmap = MapMarkerUtils.getMarkerBitmap(this, R.drawable.ic_marker_selected)
        if (defaultBitmap == null || selectedBitmap == null) {
            Log.e(TAG, "addMarkersIfReady: failed to load marker bitmaps")
            return
        }
        style.addImage(ICON_MARKER_DEFAULT, defaultBitmap, false)
        style.addImage(ICON_MARKER_SELECTED, selectedBitmap, false)
        val manager = SymbolManager(mapView, mapRef, style, null, null)
        symbolManager = manager
        manager.setIconAllowOverlap(true)
        manager.setIconIgnorePlacement(true)
        val markersLayerId = manager.layerId
        Log.d(TAG, "addMarkersIfReady: markers layerId=$markersLayerId")
        val selManager = SymbolManager(mapView, mapRef, style, null, markersLayerId)
        selectionSymbolManager = selManager
        Log.d(TAG, "addMarkersIfReady: selection layerId=${selManager.layerId} (above $markersLayerId)")
        selManager.setIconAllowOverlap(true)
        selManager.setIconIgnorePlacement(true)
        manager.addClickListener { symbol ->
            Log.d(TAG, "addMarkersIfReady: marker clicked")
            symbolToFeature[symbol]?.let { selectMarkerAndUpdateUi(symbol, it) }
            false
        }
        var minLat = 90.0
        var maxLat = -90.0
        var minLon = 180.0
        var maxLon = -180.0
        var hasValidPoints = false
        for (feature in features) {
            val coords = feature.geometry.coordinates
            if (coords.size >= 2) {
                val lon = coords[0]
                val lat = coords[1]
                val opts = SymbolOptions()
                    .withLatLng(LatLng(lat, lon))
                    .withIconImage(ICON_MARKER_DEFAULT)
                val symbol = manager.create(opts)
                symbols.add(symbol)
                symbolToFeature[symbol] = feature
                if (lat < minLat) minLat = lat
                if (lat > maxLat) maxLat = lat
                if (lon < minLon) minLon = lon
                if (lon > maxLon) maxLon = lon
                hasValidPoints = true
            }
        }
        Log.d(TAG, "addMarkersIfReady: created ${symbols.size} blue markers")
        if (hasValidPoints && !initialCameraApplied) {
            initialCameraApplied = true
            var paddedMinLat = minLat
            var paddedMaxLat = maxLat
            var paddedMinLon = minLon
            var paddedMaxLon = maxLon
            if (minLat == maxLat) {
                paddedMinLat = minLat - 0.01
                paddedMaxLat = maxLat + 0.01
            }
            if (minLon == maxLon) {
                paddedMinLon = minLon - 0.01
                paddedMaxLon = maxLon + 0.01
            }
            val latPadding = (paddedMaxLat - paddedMinLat) * 0.15
            val lonPadding = (paddedMaxLon - paddedMinLon) * 0.15
            paddedMinLat -= latPadding
            paddedMaxLat += latPadding
            paddedMinLon -= lonPadding
            paddedMaxLon += lonPadding
            val bounds = LatLngBounds.Builder()
                .include(LatLng(paddedMinLat, paddedMinLon))
                .include(LatLng(paddedMaxLat, paddedMaxLon))
                .build()
            val zoomToId = intent.getIntExtra("zoom_to_id", -1)
            fun applyZoomAndCenter() {
                if (intent.hasExtra("zoom_to_lat") && intent.hasExtra("zoom_to_lon")) {
                    if (zoomToId >= 0) {
                        val pair = symbolToFeature.entries.find { it.value.properties.database_id == zoomToId }
                        if (pair != null) {
                            val (m, f) = pair
                            val c = f.geometry.coordinates
                            if (c.size >= 2) {
                                mapRef.setCameraPosition(CameraPosition.Builder().target(LatLng(c[1], c[0])).zoom(DEFAULT_POINT_ZOOM).build())
                                selectMarkerAndUpdateUi(m, f)
                                return
                            }
                        }
                    }
                    val centerLat = intent.getDoubleExtra("zoom_to_lat", 0.0)
                    val centerLon = intent.getDoubleExtra("zoom_to_lon", 0.0)
                    mapRef.setCameraPosition(CameraPosition.Builder().target(LatLng(centerLat, centerLon)).zoom(DEFAULT_POINT_ZOOM).build())
                } else {
                    val padding = (50 * resources.displayMetrics.density).toInt()
                    val cameraPosition = mapRef.getCameraForLatLngBounds(bounds, intArrayOf(padding, padding, padding, padding))
                    cameraPosition?.let { mapRef.setCameraPosition(it) }
                }
            }
            mapView.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    mapView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    applyZoomAndCenter()
                }
            })
        } else if (!hasValidPoints && !initialCameraApplied) {
            initialCameraApplied = true
            mapRef.setCameraPosition(CameraPosition.Builder().target(LatLng(0.0, 0.0)).zoom(2.0).build())
        }
    }

    private fun updateMapAuthHeader() {
        executor.execute {
            GeovaultAuthManager.getValidAccessToken(this@MapActivity)
            runOnUiThread { if (!isDestroyed) { /* token refreshed for next requests */ } }
        }
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        updateMapAuthHeader()
        loadFeaturesFromCache()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
        // Clear selected point when leaving the map so it's not selected when returning.
        selectionSymbol?.let { selectionSymbolManager?.delete(it) }
        selectionSymbol = null
        selectedFeature = null
        lastSelectedSymbol = null
        clearSelectionUi()
    }

    override fun onDestroy() {
        mapView.removeOnDidFailLoadingMapListener(this)
        selectionSymbolManager?.onDestroy()
        selectionSymbolManager = null
        selectionSymbol = null
        symbolManager?.onDestroy()
        symbolManager = null
        executor.shutdown()
        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_INITIAL_CAMERA_APPLIED, initialCameraApplied)
        mapView.onSaveInstanceState(outState)
    }

    override fun finish() {
        super.finish()
        safeNoAnimation()
    }

    private fun safeNoAnimation() {
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }

    companion object {
        private const val KEY_INITIAL_CAMERA_APPLIED = "initial_camera_applied"
        private const val TAG = "GeoVaultMap"
        private const val RASTER_SOURCE_ID = "geovault-raster"
        private const val RASTER_LAYER_ID = "geovault-raster-layer"
        /** Layer ID below which we add raster so markers (annotations) render on top (cf. frontend moveLayer tile to bottom). */
        private const val ANNOTATIONS_LAYER_ID = "org.maplibre.annotations.points"
        /** Max zoom for raster (OSM, satellite) tiles. */
        private const val MAX_ZOOM_LEVEL = 15
        /** Max zoom for MapTiler vector maps. */
        private const val MAX_ZOOM_LEVEL_VECTOR = 18
        private const val DEFAULT_POINT_ZOOM = 12.0
        private const val ICON_MARKER_DEFAULT = "geovault-marker-default"
        private const val ICON_MARKER_SELECTED = "geovault-marker-selected"
    }
}
