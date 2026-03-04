package com.geovault.places

import com.geovault.common.CoordinateParser
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.RetrofitClient
import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
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
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class PlaceEditActivity : AppCompatActivity(), OnMapReadyCallback, MapView.OnDidFailLoadingMapListener {

    private lateinit var mapView: MapView
    private var maplibreMap: MapLibreMap? = null
    private lateinit var nameInput: EditText
    private lateinit var descriptionInput: EditText
    private lateinit var coordinatesInput: EditText
    private lateinit var coordinatesError: TextView
    private lateinit var saveButton: MaterialButton
    private lateinit var useMyLocationButton: MaterialButton
    private lateinit var btnLocationIcon: ImageView
    private lateinit var btnLocationSpinner: ImageView
    private lateinit var savingSpinner: ImageView
    private lateinit var titleText: TextView
    private lateinit var locationLoadingOverlay: View
    private lateinit var savingOverlay: View
    private lateinit var savingText: TextView
    private lateinit var savingTapHint: TextView

    private lateinit var searchPlaceButton: ImageView
    private lateinit var searchBarPanel: View
    private lateinit var searchBarInputRow: View
    private lateinit var searchPlaceInput: EditText
    private lateinit var searchPlaceCloseButton: ImageView
    private lateinit var searchPlaceSpinner: ImageView
    private lateinit var searchPlaceResults: ListView
    private lateinit var mapContainer: View
    private lateinit var searchPlaceRotationHelper: RotationHelper
    private lateinit var sourceManager: MapSourceManager

    private var latitude: Double? = null
    private var longitude: Double? = null
    private var storedAddress: String? = null
    
    private var symbolManager: SymbolManager? = null
    private var placeSymbol: Symbol? = null
    /** True when map is ready (onMapReady ran). */
    private var mapReady = false
    /** True when fetchMapSources callback ran (server configured). */
    private var sourcesFetched = false
    /** True after we've centered the map on the place once; layer switches should not re-center. */
    private var initialMapCenterApplied = false
    private var editFeature: Feature? = null
    private var originalFeature: Feature? = null
    private var isOfflineEdit: Boolean = false
    private var offlineEditIndex: Int = -1
    private var saveCall: Call<Feature>? = null
    private var addressSearchCall: Call<AddressSearchResponse>? = null
    private var pendingFeature: Feature? = null
    private var addressSearchRunnable: Runnable? = null
    private var mapSearchRunnable: Runnable? = null
    private var mapSearchCall: Call<AddressSearchResponse>? = null
    private var mapSearchResults: MutableList<AddressSearchResult> = mutableListOf()
    private lateinit var mapSearchAdapter: android.widget.BaseAdapter
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private lateinit var locationRotationHelper: RotationHelper
    private lateinit var savingRotationHelper: RotationHelper

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var skipNextCoordinatesValidation = false

    private var initialName: String = ""
    private var initialDescription: String = ""
    private var initialCoordsText: String = ""
    private var initialStoredAddress: String? = null

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
    }

    /** Zoom level when focusing on a single point (same as MapActivity zoom-to-point). */
    private val zoomLevelPoint = 12.0

    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) getCurrentLocation()
        else Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sourceManager = MapSourceManager(this)
        setContentView(R.layout.activity_place_edit)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        initViews()
        setupWindowInsets()
        mapView = findViewById(R.id.map)
        // MapLibre shows a foreground drawable until the map loads; use theme-aware color (black in dark mode).
        mapView.foreground = android.graphics.drawable.ColorDrawable(ContextCompat.getColor(this, R.color.map_underlay))
        mapView.addOnDidFailLoadingMapListener(this)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)
        fetchMapSources()
        
        // Check for edit intent
        editFeature = intent.getParcelableExtra("feature", Feature::class.java)
        originalFeature = intent.getParcelableExtra("original_feature", Feature::class.java)
        
        isOfflineEdit = intent.getBooleanExtra("is_offline_edit", false)
        offlineEditIndex = intent.getIntExtra("offline_edit_index", -1)

        if (editFeature != null) {
            populateFields(editFeature!!)
            if (isOfflineEdit) {
                titleText.text = "Edit Place (Offline)"
            } else {
                titleText.text = "Edit Place"
            }
        }

        initialName = nameInput.text.toString().trim()
        initialDescription = descriptionInput.text.toString().trim()
        initialCoordsText = coordinatesInput.text.toString().trim()
        initialStoredAddress = storedAddress

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                tryFinish()
            }
        })

        setupListeners()
        validateForm() // Initial check
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

    private fun applySelectedSource() {
        val map = maplibreMap ?: return
        if (isDestroyed) return
        // Remove current marker from the existing style before replacing it, so we never accumulate
        // symbols across layer switches (old style is replaced but clearing refs avoids any stale state).
        placeSymbol?.let { symbolManager?.delete(it) }
        placeSymbol = null
        symbolManager = null
        Log.d(TAG, "applySelectedSource: called, will setStyle")
        try {
            val effectiveId = sourceManager.getEffectiveSourceId()
            val mapMaxZoom = if (sourceManager.isVectorSource(effectiveId)) MAX_ZOOM_LEVEL_VECTOR.toDouble() else MAX_ZOOM_LEVEL.toDouble()
            map.setMaxZoomPreference(mapMaxZoom)
            if (sourceManager.isVectorSource(effectiveId)) {
                val styleUrl = sourceManager.getResolvedStyleUrl(effectiveId)
                if (!styleUrl.isNullOrBlank()) {
                    loadVectorStyle(map, styleUrl)
                } else {
                    map.setStyle(Style.Builder()) { style -> if (!isDestroyed) onStyleLoaded(style) }
                }
            } else {
                val rasterUrl = sourceManager.getRasterUrl(effectiveId)
                if (!rasterUrl.isNullOrBlank()) {
                    map.setStyle(Style.Builder()) { style ->
                        if (isDestroyed) return@setStyle
                        try {
                            // tileSize 256, maxZoom capped at 15; raster below features.
                            val tileSet = TileSet("2.1.0", rasterUrl).apply { maxZoom = MAX_ZOOM_LEVEL.toFloat() }
                            style.addSource(RasterSource(RASTER_SOURCE_ID, tileSet, 256))
                            val rasterLayer = RasterLayer(RASTER_LAYER_ID, RASTER_SOURCE_ID)
                            try {
                                style.addLayerBelow(rasterLayer, ANNOTATIONS_LAYER_ID)
                            } catch (_: Exception) {
                                style.addLayer(rasterLayer)
                            }
                        } catch (_: Exception) { }
                        if (!isDestroyed) onStyleLoaded(style)
                    }
                } else {
                    map.setStyle(Style.Builder()) { style -> if (!isDestroyed) onStyleLoaded(style) }
                }
            }
        } catch (_: Exception) {
            map.setStyle(Style.Builder()) { style -> if (!isDestroyed) onStyleLoaded(style) }
        }
    }

    override fun onDidFailLoadingMap(errorMessage: String) {
        Log.e(TAG, "Map style load failed: $errorMessage")
        runOnUiThread {
            val map = maplibreMap ?: return@runOnUiThread
            val effectiveId = sourceManager.getEffectiveSourceId()
            if (sourceManager.isVectorSource(effectiveId)) {
                Toast.makeText(this, getString(R.string.map_style_unavailable_fallback_osm), Toast.LENGTH_SHORT).show()
                loadOsmFallback(map)
            }
        }
    }

    /**
     * Load vector style from URL. Uses MapStyleCache so repeat layer switches load instantly.
     * On failure, falls back to OSM raster.
     */
    private fun loadVectorStyle(map: MapLibreMap, styleUrl: String) {
        val serverUrl = GeovaultAuthManager.getServerUrl(this).trimEnd('/')
        val isOurServer = serverUrl.isNotEmpty() && (styleUrl == serverUrl || styleUrl.startsWith("$serverUrl/"))
        val serverBase = if (isOurServer) java.net.URI.create(styleUrl).let { "${it.scheme}://${it.host}" } else null

        MapStyleCache.getStyleJson(this, styleUrl, isOurServer, serverBase) { json ->
            if (isDestroyed) return@getStyleJson
            if (!json.isNullOrBlank()) {
                map.setStyle(Style.Builder().fromJson(json)) { style -> if (!isDestroyed) onStyleLoaded(style) }
            } else {
                Toast.makeText(this@PlaceEditActivity, getString(R.string.map_style_unavailable_fallback_osm), Toast.LENGTH_SHORT).show()
                loadOsmFallback(map)
            }
        }
    }

    private fun loadOsmFallback(map: MapLibreMap) {
        val rasterUrl = sourceManager.getStreetFallbackRasterUrl()
        if (rasterUrl.isNullOrBlank()) {
            map.setStyle(Style.Builder()) { style -> if (!isDestroyed) onStyleLoaded(style) }
            return
        }
        map.setMaxZoomPreference(MAX_ZOOM_LEVEL.toDouble())
        map.setStyle(Style.Builder()) { style ->
            if (isDestroyed) return@setStyle
            try {
                style.addSource(RasterSource(RASTER_SOURCE_ID, TileSet("2.1.0", rasterUrl).apply { maxZoom = MAX_ZOOM_LEVEL.toFloat() }, 256))
                val rasterLayer = RasterLayer(RASTER_LAYER_ID, RASTER_SOURCE_ID)
                if (!isDestroyed) onStyleLoaded(style)
                val mgr = symbolManager
                mapView.post {
                    if (isDestroyed) return@post
                    val s = map.style ?: return@post
                    try {
                        if (mgr != null) s.addLayerBelow(rasterLayer, mgr.layerId) else s.addLayer(rasterLayer)
                    } catch (_: Exception) {
                        try { s.addLayer(rasterLayer) } catch (_: Exception) { }
                    }
                }
            } catch (_: Exception) { }
        }
    }

    private fun onStyleLoaded(style: Style) {
        // Do not call symbolManager.onDestroy() here: the previous style was already replaced
        // by setStyle(), so the old manager's native layer is invalid and onDestroy() can throw
        // (wstring_convert / nativeGetId). Just clear references.
        Log.d(TAG, "onStyleLoaded: clearing old refs, style.uri=${style.uri}")
        symbolManager = null
        placeSymbol = null
        val bitmap = MapMarkerUtils.getMarkerBitmap(this, R.drawable.ic_marker_default)
        Log.d(TAG, "onStyleLoaded: bitmap=${if (bitmap != null) "${bitmap.width}x${bitmap.height}" else "null"}, iconId=$ICON_MARKER_PLACE")
        if (bitmap != null) {
            style.addImage(ICON_MARKER_PLACE, bitmap, false)
            Log.d(TAG, "onStyleLoaded: addImage($ICON_MARKER_PLACE) ok")
        } else {
            Log.e(TAG, "onStyleLoaded: bitmap null, marker icon will be missing/black")
        }
        val map = maplibreMap ?: return
        val manager = SymbolManager(mapView, map, style, null, null)
        symbolManager = manager
        manager.setIconAllowOverlap(true)
        Log.d(TAG, "onStyleLoaded: new SymbolManager layerId=${manager.layerId}, lat=$latitude lon=$longitude")
        latitude?.let { lat ->
            longitude?.let { lon ->
                Log.d(TAG, "onStyleLoaded: calling updateMarker($lat, $lon)")
                updateMarker(lat, lon)
                if (!initialMapCenterApplied) {
                    initialMapCenterApplied = true
                    zoomToPoint(lat, lon)
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
        setupMap()
        // Load style once: when no server we load now; when server configured we wait for fetchSources callback.
        if (sourcesFetched || GeovaultAuthManager.getServerUrl(this).isEmpty()) {
            applySelectedSource()
        }
    }

    private fun initViews() {
        nameInput = findViewById(R.id.placeNameInput)
        descriptionInput = findViewById(R.id.placeDescriptionInput)
        coordinatesInput = findViewById(R.id.coordinatesInput)
        coordinatesError = findViewById(R.id.coordinatesError)
        saveButton = findViewById(R.id.saveButton)
        useMyLocationButton = findViewById(R.id.useMyLocationButton)
        saveButton = findViewById(R.id.saveButton)
        useMyLocationButton = findViewById(R.id.useMyLocationButton)
        btnLocationIcon = findViewById(R.id.btnLocationIcon)
        btnLocationSpinner = findViewById(R.id.btnLocationSpinner)
        savingSpinner = findViewById(R.id.savingSpinner)
        locationLoadingOverlay = findViewById(R.id.locationButtonContent)
        titleText = findViewById(R.id.titleText)
        savingOverlay = findViewById(R.id.savingOverlay)
        savingText = findViewById(R.id.savingText)
        savingTapHint = findViewById(R.id.savingTapHint)

        locationRotationHelper = RotationHelper(btnLocationSpinner)
        savingRotationHelper = RotationHelper(savingSpinner)

        findViewById<View>(R.id.closeButton).setOnClickListener { tryFinish() }
        findViewById<View>(R.id.cancelButton).setOnClickListener { tryFinish() }

        searchPlaceButton = findViewById(R.id.searchPlaceButton)
        searchBarPanel = findViewById(R.id.searchBarPanel)
        searchBarInputRow = findViewById(R.id.searchBarInputRow)
        searchPlaceInput = findViewById(R.id.searchPlaceInput)
        searchPlaceCloseButton = findViewById(R.id.searchPlaceCloseButton)
        searchPlaceSpinner = findViewById(R.id.searchPlaceSpinner)
        searchPlaceResults = findViewById(R.id.searchPlaceResults)
        mapContainer = findViewById(R.id.mapContainer)
        searchPlaceRotationHelper = RotationHelper(searchPlaceSpinner)

        mapSearchAdapter = object : android.widget.BaseAdapter() {
            override fun getCount(): Int = mapSearchResults.size
            override fun getItem(position: Int): AddressSearchResult = mapSearchResults[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(this@PlaceEditActivity)
                    .inflate(R.layout.item_search_result, parent, false)
                val result = mapSearchResults[position]
                val title = view.findViewById<TextView>(R.id.searchResultTitle)
                val subtitle = view.findViewById<TextView>(R.id.searchResultSubtitle)
                title.text = result.text ?: result.place_name ?: ""
                val sub = result.place_name?.takeIf { it != title.text }
                subtitle.text = sub
                subtitle.visibility = if (sub.isNullOrBlank()) View.GONE else View.VISIBLE
                return view
            }
        }
        searchPlaceResults.adapter = mapSearchAdapter
        setupMapSearch()
    }

    private fun setupWindowInsets() {
        val rootView = findViewById<View>(R.id.rootLayout)
        val headerView = findViewById<View>(R.id.headerLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            
            headerView.updatePadding(top = systemBars.top + 20)
            
            // When focus is in the map search box, don't push the form up (ignore IME inset).
            // Otherwise use the larger of navigation bar or keyboard.
            val bottomInset = if (searchPlaceInput.hasFocus()) systemBars.bottom
                else if (ime.bottom > systemBars.bottom) ime.bottom else systemBars.bottom
            view.updatePadding(bottom = bottomInset)
            
            windowInsets
        }
    }

    private fun setupMap() {
        val map = maplibreMap ?: return
        Log.d(TAG, "setupMap: registering map click listener")
        map.addOnMapClickListener { point ->
            Log.d(TAG, "setupMap: map tapped lat=${point.latitude} lon=${point.longitude} symbolManager=${symbolManager != null} placeSymbol=${placeSymbol != null}")
            updateCoords(point.latitude, point.longitude, null)
            true
        }
        findViewById<View>(R.id.mapToggle).setOnClickListener {
            val nextSourceId = sourceManager.getNextSourceId()
            sourceManager.setSelectedSourceId(nextSourceId)
            applySelectedSource()
        }
        // Only set default camera when no place coords; initial center on place happens in onStyleLoaded (once).
        // New place: show entire world (zoom 0).
        if (latitude == null || longitude == null) {
            map.setCameraPosition(CameraPosition.Builder().target(LatLng(0.0, 0.0)).zoom(0.0).build())
        }
    }

    private fun setupListeners() {
        nameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { validateForm() }
        })
        coordinatesInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (addressSearchCall != null) {
                    addressSearchCall?.cancel()
                    addressSearchCall = null
                    setLocationLoading(false)
                }
                addressSearchRunnable?.let { handler.removeCallbacks(it) }
                addressSearchRunnable = null
                if (skipNextCoordinatesValidation) {
                    skipNextCoordinatesValidation = false
                    return
                }
                val r = Runnable { validateCoordinatesFromInput() }
                addressSearchRunnable = r
                handler.postDelayed(r, 1000)
            }
        })
        useMyLocationButton.setOnClickListener {
            currentFocus?.let { focus ->
                focus.clearFocus()
                (getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.hideSoftInputFromWindow(focus.windowToken, 0)
            }
            checkLocationPermissionAndGet()
        }
        saveButton.setOnClickListener { savePlace() }
    }

    private fun setupMapSearch() {
        searchPlaceButton.setOnClickListener {
            if (searchBarPanel.visibility == View.VISIBLE) {
                closeSearchBar()
                return@setOnClickListener
            }
            searchBarPanel.visibility = View.VISIBLE
            searchPlaceResults.visibility = View.GONE
            searchPlaceInput.requestFocus()
            updateSearchCloseButtonVisibility()
            ViewCompat.requestApplyInsets(findViewById(R.id.rootLayout))
            (getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(searchPlaceInput, InputMethodManager.SHOW_IMPLICIT)
        }
        searchPlaceCloseButton.setOnClickListener { closeSearchBar() }
        searchPlaceInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                handler.post { performMapSearch() }
                true
            } else false
        }
        searchPlaceInput.setOnFocusChangeListener { _, _ ->
            ViewCompat.requestApplyInsets(findViewById(R.id.rootLayout))
        }
        searchPlaceInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                mapSearchRunnable?.let { handler.removeCallbacks(it) }
                mapSearchRunnable = null
                mapSearchCall?.cancel()
                mapSearchCall = null
                searchPlaceRotationHelper.stop()
                val query = searchPlaceInput.text.toString().trim()
                updateSearchCloseButtonVisibility()
                if (query.isEmpty()) {
                    mapSearchResults.clear()
                    mapSearchAdapter.notifyDataSetChanged()
                    searchPlaceResults.visibility = View.GONE
                    setSearchBarCornersForResults(false)
                    return
                }
                val r = Runnable { performMapSearch() }
                mapSearchRunnable = r
                handler.postDelayed(r, 300)
            }
        })
        searchPlaceResults.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val result = mapSearchResults.getOrNull(position) ?: return@OnItemClickListener
            applyMapSearchResult(result)
        }
    }

    private fun performMapSearch() {
        mapSearchRunnable = null
        val query = searchPlaceInput.text.toString().trim()
        if (query.isEmpty()) return
        val parsed = CoordinateParser.parse(query)
        if (parsed != null) {
            val (lat, lon) = parsed
            val synthetic = AddressSearchResult(
                coordinates = listOf(lon, lat),
                place_name = "Coordinates: ${String.format("%.6f", lat)}°, ${String.format("%.6f", lon)}°",
                text = null
            )
            mapSearchResults.clear()
            mapSearchResults.add(synthetic)
            mapSearchAdapter.notifyDataSetChanged()
            setSearchBarCornersForResults(true)
            searchPlaceResults.visibility = View.VISIBLE
            searchPlaceResults.post { updateSearchResultsListHeight() }
            return
        }
        val serverUrl = GeovaultAuthManager.getServerUrl(this)
        if (serverUrl.isEmpty()) {
            mapSearchResults.clear()
            mapSearchAdapter.notifyDataSetChanged()
            searchPlaceResults.visibility = View.GONE
            return
        }
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(this, baseUrl).create(GeovaultApi::class.java)
        mapSearchCall = api.geocodingSearch(query)
        searchPlaceRotationHelper.start()
        mapSearchCall!!.enqueue(object : Callback<AddressSearchResponse> {
            override fun onResponse(call: Call<AddressSearchResponse>, response: Response<AddressSearchResponse>) {
                mapSearchCall = null
                searchPlaceRotationHelper.stop()
                if (call.isCanceled) return
                val features = response.body()?.data?.features
                mapSearchResults.clear()
                if (!features.isNullOrEmpty()) mapSearchResults.addAll(features)
                mapSearchAdapter.notifyDataSetChanged()
                if (mapSearchResults.isEmpty()) {
                    setSearchBarCornersForResults(false)
                    searchPlaceResults.visibility = View.GONE
                } else {
                    setSearchBarCornersForResults(true)
                    searchPlaceResults.visibility = View.VISIBLE
                    searchPlaceResults.post { updateSearchResultsListHeight() }
                }
            }
            override fun onFailure(call: Call<AddressSearchResponse>, t: Throwable) {
                mapSearchCall = null
                searchPlaceRotationHelper.stop()
                if (call.isCanceled) return
                mapSearchResults.clear()
                mapSearchAdapter.notifyDataSetChanged()
                setSearchBarCornersForResults(false)
                searchPlaceResults.visibility = View.GONE
            }
        })
    }

    private fun applyMapSearchResult(result: AddressSearchResult) {
        val coords = result.coordinates
        if (coords == null || coords.size < 2) return
        val lon = coords[0]
        val lat = coords[1]
        val displayText = result.place_name ?: result.text
        updateCoords(lat, lon, displayText)
        zoomToPoint(lat, lon)
        closeSearchBar()
    }

    private fun updateSearchCloseButtonVisibility() {
        searchPlaceCloseButton.visibility =
            if (searchPlaceInput.text.toString().trim().isEmpty()) View.GONE else View.VISIBLE
    }

    private fun closeSearchBar() {
        searchPlaceInput.setText("")
        searchPlaceInput.clearFocus()
        mapSearchResults.clear()
        mapSearchAdapter.notifyDataSetChanged()
        setSearchBarCornersForResults(false)
        searchBarPanel.visibility = View.GONE
        searchPlaceResults.visibility = View.GONE
        searchPlaceCloseButton.visibility = View.GONE
        (getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(searchPlaceInput.windowToken, 0)
        ViewCompat.requestApplyInsets(findViewById(R.id.rootLayout))
    }

    private fun setSearchBarCornersForResults(visible: Boolean) {
        searchBarInputRow.background = ContextCompat.getDrawable(
            this,
            if (visible) R.drawable.bg_search_bar_top_rounded else R.drawable.bg_search_bar_standalone
        )
    }

    private fun updateSearchResultsListHeight() {
        val count = mapSearchResults.size
        if (count == 0) return
        val rowHeightPx = (56 * resources.displayMetrics.density).toInt()
        val contentHeight = count * rowHeightPx
        val availableHeight = if (mapContainer.height > 0 && searchBarInputRow.height > 0) {
            val panelPadding = searchBarPanel.paddingTop + searchBarPanel.paddingBottom
            (mapContainer.height - searchBarInputRow.height - panelPadding - 24).coerceAtLeast(0)
        } else {
            (220 * resources.displayMetrics.density).toInt()
        }
        val height = contentHeight.coerceAtMost(availableHeight).coerceAtLeast(rowHeightPx)
        val params = searchPlaceResults.layoutParams ?: return
        params.height = height
        searchPlaceResults.requestLayout()
    }

    // Unified rule (same as web): try parse as coordinates; if fail, geocode only when
    // address-like (has letter not N/S/E/W/D); else if looks like coordinate attempt show error;
    // else clear with no error (e.g. "123 " while typing).
    private fun validateCoordinatesFromInput() {
        addressSearchRunnable = null
        coordinatesError.visibility = View.GONE
        coordinatesError.text = ""
        val input = coordinatesInput.text.toString().trim()
        if (input.isEmpty()) {
            latitude = null
            longitude = null
            storedAddress = null
            placeSymbol?.let { symbolManager?.delete(it) }
            placeSymbol = null
            validateForm()
            return
        }
        val parsed = CoordinateParser.parse(input)
        if (parsed != null) {
            latitude = parsed.first
            longitude = parsed.second
            storedAddress = null
            skipNextCoordinatesValidation = true
            coordinatesInput.setText(String.format("%.6f, %.6f", parsed.first, parsed.second))
            coordinatesInput.setSelection(coordinatesInput.text?.length ?: 0)
            updateMarker(parsed.first, parsed.second)
            zoomToPoint(parsed.first, parsed.second)
            validateForm()
            return
        }
        if (input.any { it.isLetter() && it.lowercaseChar() !in "nsewd" }) {
            performAddressSearch(input)
            return
        }
        if (CoordinateParser.looksLikeCoordinates(input)) {
            showCoordinatesErrorAndClear()
            return
        }
        latitude = null
        longitude = null
        storedAddress = null
        placeSymbol?.let { symbolManager?.delete(it) }
        placeSymbol = null
        validateForm()
    }

    private fun showCoordinatesErrorAndClear() {
        coordinatesError.text = "Invalid coordinate format"
        coordinatesError.visibility = View.VISIBLE
        latitude = null
        longitude = null
        storedAddress = null
        validateForm()
    }

    private fun performAddressSearch(query: String) {
        addressSearchCall?.cancel()
        val serverUrl = GeovaultAuthManager.getServerUrl(this)
        if (serverUrl.isEmpty()) {
            coordinatesError.text = "Geocoding failed"
            coordinatesError.visibility = View.VISIBLE
            validateForm()
            return
        }
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(this, baseUrl).create(GeovaultApi::class.java)
        setLocationLoading(true)
        addressSearchCall = api.geocodingSearch(query)
        addressSearchCall!!.enqueue(object : Callback<AddressSearchResponse> {
            override fun onResponse(call: Call<AddressSearchResponse>, response: Response<AddressSearchResponse>) {
                addressSearchCall = null
                setLocationLoading(false)
                if (call.isCanceled) return
                if (!response.isSuccessful) {
                    coordinatesError.text = response.message() ?: "Geocoding failed"
                    coordinatesError.visibility = View.VISIBLE
                    validateForm()
                    return
                }
                val features = response.body()?.data?.features
                if (!features.isNullOrEmpty()) {
                    val first = features[0]
                    val coords = first.coordinates
                    if (coords != null && coords.size >= 2) {
                        val lat = coords[1]
                        val lon = coords[0]
                        latitude = lat
                        longitude = lon
                        storedAddress = first.place_name ?: first.text ?: query
                        skipNextCoordinatesValidation = true
                        coordinatesInput.setText(storedAddress)
                        coordinatesInput.setSelection(coordinatesInput.text?.length ?: 0)
                        coordinatesError.visibility = View.GONE
                        updateMarker(lat, lon)
                        zoomToPoint(lat, lon)
                    } else {
                        coordinatesError.text = "Address not found"
                        coordinatesError.visibility = View.VISIBLE
                    }
                } else {
                    coordinatesError.text = "Address not found"
                    coordinatesError.visibility = View.VISIBLE
                }
                validateForm()
            }
            override fun onFailure(call: Call<AddressSearchResponse>, t: Throwable) {
                addressSearchCall = null
                setLocationLoading(false)
                if (call.isCanceled) return
                coordinatesError.text = t.message ?: "Geocoding failed"
                coordinatesError.visibility = View.VISIBLE
                validateForm()
            }
        })
    }

    private fun populateFields(feature: Feature) {
        nameInput.setText(feature.properties.name)
        descriptionInput.setText(feature.properties.description)
        val coords = feature.geometry.coordinates
        if (coords.size >= 2) {
            val address = feature.properties.address
            val displayText = if (!address.isNullOrBlank()) address else null
            updateCoords(coords[1], coords[0], displayText)
            maplibreMap?.let { map ->
                map.setCameraPosition(CameraPosition.Builder().target(LatLng(coords[1], coords[0])).zoom(zoomLevelPoint).build())
            }
        }
    }

    private fun zoomToPoint(lat: Double, lon: Double) {
        maplibreMap?.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(lat, lon)).zoom(zoomLevelPoint).build()))
    }

    private fun updateCoords(lat: Double, lon: Double, displayText: String?) {
        Log.d(TAG, "updateCoords: lat=$lat lon=$lon")
        latitude = lat
        longitude = lon
        storedAddress = if (displayText.isNullOrBlank()) null else displayText
        skipNextCoordinatesValidation = true
        coordinatesInput.setText(displayText ?: String.format("%.6f, %.6f", lat, lon))
        coordinatesError.visibility = View.GONE
        coordinatesError.text = ""
        updateMarker(lat, lon)
        validateForm()
    }

    private fun updateMarker(lat: Double, lon: Double) {
        val manager = symbolManager
        if (manager == null) {
            Log.e(TAG, "updateMarker: symbolManager is null, skipping lat=$lat lon=$lon")
            return
        }
        val latLng = LatLng(lat, lon)
        val hadExisting = placeSymbol != null
        placeSymbol?.let { sym ->
            Log.d(TAG, "updateMarker: deleting existing symbol")
            manager.delete(sym)
        }
        placeSymbol = manager.create(
            SymbolOptions().withLatLng(latLng).withIconImage(ICON_MARKER_PLACE)
        )
        Log.d(TAG, "updateMarker: hadExisting=$hadExisting created new at $lat,$lon icon=$ICON_MARKER_PLACE")
    }

    private fun checkLocationPermissionAndGet() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ->
                getCurrentLocation()
            else -> requestLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun getCurrentLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        
        setLocationLoading(true)
        
        val cts = CancellationTokenSource()
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
            .addOnSuccessListener { location: Location? ->
                location?.let {
                    setLocationLoading(false)
                    updateCoords(it.latitude, it.longitude, null)
                    zoomToPoint(it.latitude, it.longitude)
                } ?: run {
                    // Fallback to last location if fresh one fails
                    fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                        setLocationLoading(false)
                        lastLoc?.let {
                            updateCoords(it.latitude, it.longitude, null)
                            zoomToPoint(it.latitude, it.longitude)
                        } ?: run {
                            Toast.makeText(this, "Could not get location", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .addOnFailureListener {
                setLocationLoading(false)
                Toast.makeText(this, "Location request failed: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun validateForm() {
        val name = nameInput.text.toString().trim()
        val isValid = name.isNotEmpty() && latitude != null && longitude != null
        saveButton.isEnabled = isValid
        saveButton.alpha = if (isValid) 1.0f else 0.5f
    }

    private fun showSavingOverlay(message: String = "Saving...") {
        savingText.text = message
        savingSpinner.visibility = View.VISIBLE
        savingOverlay.visibility = View.VISIBLE
        startSavingAnimation()
        saveButton.isEnabled = false

        val isSavingOffline = message == "Saving offline..."
        if (isSavingOffline) {
            savingOverlay.setOnClickListener(null)
            savingOverlay.isClickable = false
            savingTapHint.visibility = View.INVISIBLE
        } else {
            savingOverlay.isClickable = true
            savingTapHint.visibility = View.VISIBLE
            savingOverlay.setOnClickListener {
                saveCall?.cancel()
                saveCall = null
                if (pendingFeature != null) {
                    showSavingOverlay("Saving offline...")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        saveOffline(pendingFeature!!)
                    }, 300)
                }
            }
        }
    }

    private fun hideSavingOverlay() {
        savingOverlay.visibility = View.GONE
        stopSavingAnimation()
        validateForm() // Re-validate to restore button state
    }

    private fun hasUnsavedChanges(): Boolean {
        val currentName = nameInput.text.toString().trim()
        val currentDescription = descriptionInput.text.toString().trim()
        val currentCoordsText = coordinatesInput.text.toString().trim()
        return currentName != initialName ||
            currentDescription != initialDescription ||
            currentCoordsText != initialCoordsText ||
            storedAddress != initialStoredAddress
    }

    private fun tryFinish() {
        if (!hasUnsavedChanges()) {
            finish()
            return
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Discard changes?")
            .setMessage("You have unsaved changes. Are you sure you want to leave?")
            .setPositiveButton("Discard") { _, _ -> finish() }
            .setNegativeButton("Cancel", null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(
            ContextCompat.getColor(this, com.geovault.common.R.color.gv_common_error_red)
        )
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(
            ContextCompat.getColor(this, com.geovault.common.R.color.gv_common_dialog_negative_button)
        )
    }

    private fun savePlace() {
        val name = nameInput.text.toString().trim()
        val lat = latitude
        val lon = longitude

        if (name.isEmpty()) {
            nameInput.error = "Name is required"
            return
        }
        if (lat == null || lon == null) {
            Toast.makeText(this, "Coordinates are required", Toast.LENGTH_SHORT).show()
            return
        }

        val feature = Feature(
            type = "Feature",
            geometry = Geometry(type = "Point", coordinates = listOf(lon, lat)),
            properties = Properties(
                database_id = editFeature?.properties?.database_id,
                name = name,
                description = descriptionInput.text.toString().trim(),
                created_at = editFeature?.properties?.created_at,
                address = storedAddress
            )
        )

        val serverUrl = GeovaultAuthManager.getServerUrl(this)
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(this, baseUrl).create(GeovaultApi::class.java)

        pendingFeature = feature
        
        // Offline edits must never call updatePlace here — they go back to MainActivity
        // so sync (with conflict detection) can run. Otherwise we'd overwrite server changes.
        if (isOfflineEdit) {
            saveOffline(feature)
            return
        }
        
        showSavingOverlay()
        if (editFeature != null) {
            saveCall = api.updatePlace(editFeature!!.properties.database_id!!, feature)
            saveCall?.enqueue(object : Callback<Feature> {
                override fun onResponse(call: Call<Feature>, response: Response<Feature>) {
                    saveCall = null
                    pendingFeature = null
                    hideSavingOverlay()
                    if (response.isSuccessful) {
                        val savedFeature = response.body()
                        if (savedFeature != null) {
                            val data = android.content.Intent().apply { putExtra("updated_feature", savedFeature) }
                            setResult(RESULT_OK, data)
                        } else {
                            setResult(RESULT_OK)
                        }
                        finish()
                    } else {
                        showSavingOverlay("Saving offline...")
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            saveOffline(feature)
                        }, 300)
                    }
                }

                override fun onFailure(call: Call<Feature>, t: Throwable) {
                    showSavingOverlay("Saving offline...")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        saveOffline(feature)
                    }, 300)
                }
            })
        } else {
            // New place - try online first, fallback to offline
            saveCall = api.createPlace(feature)
            saveCall?.enqueue(object : Callback<Feature> {
                override fun onResponse(call: Call<Feature>, response: Response<Feature>) {
                    saveCall = null
                    pendingFeature = null
                    hideSavingOverlay()
                    if (response.isSuccessful) {
                        val savedFeature = response.body()
                        if (savedFeature != null) {
                            val data = android.content.Intent().apply { putExtra("updated_feature", savedFeature) }
                            setResult(RESULT_OK, data)
                        } else {
                            setResult(RESULT_OK)
                        }
                        finish()
                    } else {
                        showSavingOverlay("Saving offline...")
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            saveOffline(feature)
                        }, 300)
                    }
                }

                override fun onFailure(call: Call<Feature>, t: Throwable) {
                    showSavingOverlay("Saving offline...")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        saveOffline(feature)
                    }, 300)
                }
            })
        }
    }

    private fun saveOffline(feature: Feature) {
        val intent = android.content.Intent()
        intent.putExtra("offline_feature", feature)
        // Use the existing originalFeature if we're editing an offline item, otherwise use editFeature
        intent.putExtra("original_feature", originalFeature ?: editFeature)
        intent.putExtra("offline_edit_index", offlineEditIndex)
        setResult(RESULT_OK, intent)
        finish()
    }

    override fun finish() {
        super.finish()
        safeNoAnimation()
    }

    private fun safeNoAnimation() {
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }

    override fun onStart() {
        super.onStart()
        if (::mapView.isInitialized) mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        if (::mapView.isInitialized) mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        addressSearchRunnable?.let { handler.removeCallbacks(it) }
        addressSearchRunnable = null
        addressSearchCall?.cancel()
        addressSearchCall = null
        mapSearchRunnable?.let { handler.removeCallbacks(it) }
        mapSearchRunnable = null
        mapSearchCall?.cancel()
        mapSearchCall = null
        searchPlaceRotationHelper.stop()
        locationRotationHelper.stop()
        savingRotationHelper.stop(hide = false)
        if (::mapView.isInitialized) mapView.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (::mapView.isInitialized) mapView.onStop()
    }

    override fun onDestroy() {
        if (::mapView.isInitialized) mapView.removeOnDidFailLoadingMapListener(this)
        symbolManager?.onDestroy()
        symbolManager = null
        placeSymbol = null
        if (::mapView.isInitialized) mapView.onDestroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::mapView.isInitialized) mapView.onSaveInstanceState(outState)
    }
    private fun setLocationLoading(loading: Boolean) {
        if (loading) {
            btnLocationIcon.visibility = View.GONE
            useMyLocationButton.isEnabled = false
            locationRotationHelper.start()
        } else {
            btnLocationIcon.visibility = View.VISIBLE
            locationRotationHelper.stop()
            useMyLocationButton.isEnabled = true
        }
    }

    private fun startSavingAnimation() {
        savingRotationHelper.start()
    }

    private fun stopSavingAnimation() {
        savingRotationHelper.stop(hide = false)
    }

    companion object {
        private const val TAG = "PlaceEditActivity"
        private const val RASTER_SOURCE_ID = "geovault-raster"
        private const val RASTER_LAYER_ID = "geovault-raster-layer"
        private const val ANNOTATIONS_LAYER_ID = "org.maplibre.annotations.points"
        /** Max zoom for raster (OSM, satellite) tiles. */
        private const val MAX_ZOOM_LEVEL = 15
        /** Max zoom for MapTiler vector maps. */
        private const val MAX_ZOOM_LEVEL_VECTOR = 18
        private const val ICON_MARKER_PLACE = "geovault-marker-place"
    }
}
