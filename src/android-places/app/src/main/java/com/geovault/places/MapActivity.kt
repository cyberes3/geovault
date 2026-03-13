package com.geovault.places

import android.content.Intent
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.R as CommonR
import com.geovault.common.map.GeoVaultMapFragment
import com.geovault.common.map.MapLibreManager
import com.geovault.common.map.MapMarkerUtils
import com.geovault.common.map.OverlappingPointsPopup
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import android.view.ViewTreeObserver
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.plugins.annotation.Symbol
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.android.plugins.annotation.SymbolOptions
import android.util.Log
import java.util.ArrayList
import java.util.concurrent.Executors

class MapActivity : AppCompatActivity() {

    private var mapFragment: GeoVaultMapFragment? = null
    private lateinit var features: ArrayList<Feature>
    private var symbolManager: SymbolManager? = null
    private var selectionSymbolManager: SymbolManager? = null
    private var selectionSymbol: Symbol? = null
    private var lastSelectedSymbol: Symbol? = null
    private val symbols = mutableListOf<Symbol>()
    private val symbolToFeature = mutableMapOf<Symbol, Feature>()
    private var selectedFeature: Feature? = null
    private val executor = Executors.newSingleThreadExecutor()
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
        setContentView(R.layout.activity_map)
        val rootView = findViewById<View>(R.id.rootLayout)
        val headerView = findViewById<View>(R.id.headerLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            headerView.updatePadding(top = insets.top + 20)
            view.updatePadding(bottom = insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
        mapFragment = supportFragmentManager.findFragmentById(R.id.gv_common_map_fragment) as? GeoVaultMapFragment
        initialCameraApplied = savedInstanceState?.getBoolean(KEY_INITIAL_CAMERA_APPLIED, false) ?: false
        features = ArrayList(cache.getDisplayFeatures())
        updateMapAuthHeader()

        mapFragment?.setCallback(object : GeoVaultMapFragment.Callback {
            override fun onMapReady(map: MapLibreMap, style: Style) {
                setupMapClickListener(map)
                applyStyleLoaded(map)
            }
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                startActivity(Intent(this@MapActivity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                })
                safeNoAnimation()
            }
        })
        setupMapUi()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadFeaturesFromCache()
        applyZoomFromIntent()
    }

    private fun applyZoomFromIntent() {
        if (!intent.hasExtra("zoom_to_lat") || !intent.hasExtra("zoom_to_lon")) return
        val map = mapFragment?.maplibreMap ?: return
        val mapManager = mapFragment?.mapManager ?: return
        val centerLat = intent.getDoubleExtra("zoom_to_lat", 0.0)
        val centerLon = intent.getDoubleExtra("zoom_to_lon", 0.0)
        val zoomToId = intent.getIntExtra("zoom_to_id", -1)
        mapManager.moveCameraWithPadding(map, CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(centerLat, centerLon)).zoom(MapLibreManager.DEFAULT_POINT_ZOOM).build()))
        if (zoomToId >= 0) {
            val pair = symbolToFeature.entries.find { it.value.properties.database_id == zoomToId }
            pair?.let { (symbol, feature) -> selectMarkerAndUpdateUi(symbol, feature) }
        }
    }

    private fun loadFeaturesFromCache() {
        features.clear()
        features.addAll(cache.getDisplayFeatures())
        refreshMarkers()
    }

    private fun refreshMarkers() {
        val map = mapFragment?.maplibreMap ?: return
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

    private fun setupMapClickListener(map: MapLibreMap) {
        val mapView = mapFragment?.mapView ?: return
        val hitRadiusPx = (20 * resources.displayMetrics.density).toInt().toFloat()
        map.addOnMapClickListener { latLng ->
            val tapScreen = map.projection.toScreenLocation(latLng)
            val tapX = tapScreen.x
            val tapY = tapScreen.y
            val nearTap = symbolToFeature.entries.filter { (symbol, _) ->
                val symScreen = map.projection.toScreenLocation(symbol.latLng)
                val dx = symScreen.x - tapX
                val dy = symScreen.y - tapY
                (dx * dx + dy * dy) <= hitRadiusPx * hitRadiusPx
            }
            when {
                nearTap.isEmpty() -> false
                nearTap.size == 1 -> {
                    val (symbol, feature) = nearTap[0]
                    selectMarkerAndUpdateUi(symbol, feature)
                    true
                }
                else -> {
                    val names = nearTap.map { (_, f) -> f.properties.name?.takeIf { n -> n.isNotBlank() } ?: "" }
                    val tapXi = tapX.toInt()
                    val tapYi = tapY.toInt()
                    OverlappingPointsPopup(this, mapView, names, tapXi, tapYi) { index ->
                        val (symbol, feature) = nearTap[index]
                        selectMarkerAndUpdateUi(symbol, feature)
                    }.show()
                    true
                }
            }
        }
    }

    private fun applyStyleLoaded(map: MapLibreMap) {
        Log.d(TAG, "applyStyleLoaded: adding markers")
        addMarkersIfReady(map)
    }

    private fun moveSelectionLayerToTop() {
        val style = mapFragment?.maplibreMap?.style ?: return
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

    private fun setupMapUi() {
        findViewById<View>(R.id.settingsButton).setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
            safeNoAnimation()
        }
        findViewById<android.widget.TextView>(R.id.placeName)
        findViewById<android.widget.TextView>(R.id.placeDescription)
        findViewById<android.widget.Button>(R.id.viewInListButton)
        findViewById<android.widget.Button>(R.id.editPlaceButton)
        findViewById<android.widget.Button>(R.id.navigateButton).setOnClickListener {
            selectedFeature?.let { feature ->
                val serverUrl = GeovaultAuthManager.getServerUrl(this)
                NavigationHelper.navigateToPlace(this, feature, serverUrl)
            }
        }
        findViewById<android.widget.Button>(R.id.editPlaceButton).setOnClickListener {
            selectedFeature?.let { feature ->
                val intent = android.content.Intent(this, PlaceEditActivity::class.java)
                intent.putExtra("feature", feature)
                editLauncher.launch(intent)
                safeNoAnimation()
            }
        }
        findViewById<android.widget.Button>(R.id.viewInListButton).setOnClickListener {
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
        moveSelectionLayerToTop()

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

    private fun clearSelectionUi() {
        findViewById<android.widget.TextView>(R.id.placeName).text = getString(CommonR.string.gv_common_map_select_place)
        findViewById<android.widget.TextView>(R.id.placeDescription).text = getString(CommonR.string.gv_common_map_tap_marker_hint)
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
        val mapRef = map ?: mapFragment?.maplibreMap ?: return
        val mapView = mapFragment?.mapView ?: return
        val mapManager = mapFragment?.mapManager ?: return
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
        val defaultBitmap = MapMarkerUtils.getMarkerBitmap(this, CommonR.drawable.gv_common_ic_marker_default)
        val selectedBitmap = MapMarkerUtils.getMarkerBitmap(this, CommonR.drawable.gv_common_ic_marker_selected)
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
        val selManager = SymbolManager(mapView, mapRef, style, null, markersLayerId)
        selectionSymbolManager = selManager
        selManager.setIconAllowOverlap(true)
        selManager.setIconIgnorePlacement(true)
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
                                mapRef.setCameraPosition(CameraPosition.Builder().target(LatLng(c[1], c[0])).zoom(MapLibreManager.DEFAULT_POINT_ZOOM).build())
                                selectMarkerAndUpdateUi(m, f)
                                return
                            }
                        }
                    }
                    val centerLat = intent.getDoubleExtra("zoom_to_lat", 0.0)
                    val centerLon = intent.getDoubleExtra("zoom_to_lon", 0.0)
                    mapRef.setCameraPosition(CameraPosition.Builder().target(LatLng(centerLat, centerLon)).zoom(MapLibreManager.DEFAULT_POINT_ZOOM).build())
                } else {
                    val padding = (50 * resources.displayMetrics.density).toInt()
                    val update = CameraUpdateFactory.newLatLngBounds(bounds, padding)
                    mapManager.moveCameraWithPadding(mapRef, update)
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
            mapManager.moveCameraWithPadding(mapRef, CameraUpdateFactory.newLatLngZoom(LatLng(0.0, 0.0), 2.0))
        }
    }

    private fun updateMapAuthHeader() {
        executor.execute {
            GeovaultAuthManager.getValidAccessToken(this@MapActivity)
            runOnUiThread { if (!isDestroyed) { /* token refreshed for next requests */ } }
        }
    }

    override fun onResume() {
        super.onResume()
        updateMapAuthHeader()
        loadFeaturesFromCache()
    }

    override fun onStop() {
        super.onStop()
        selectionSymbol?.let { selectionSymbolManager?.delete(it) }
        selectionSymbol = null
        selectedFeature = null
        lastSelectedSymbol = null
        clearSelectionUi()
    }

    override fun onDestroy() {
        selectionSymbolManager?.onDestroy()
        selectionSymbolManager = null
        selectionSymbol = null
        symbolManager?.onDestroy()
        symbolManager = null
        executor.shutdown()
        mapFragment = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_INITIAL_CAMERA_APPLIED, initialCameraApplied)
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
        private const val ICON_MARKER_DEFAULT = "geovault-marker-default"
        private const val ICON_MARKER_SELECTED = "geovault-marker-selected"
    }
}
