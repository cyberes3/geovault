package com.geovault.places

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.util.BoundingBox
import java.util.ArrayList

import android.os.Build

import android.view.View
import android.view.ViewTreeObserver
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

class MapActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var features: ArrayList<Feature>
    private var lastSelectedMarker: Marker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load OSMDroid configuration
        val ctx = applicationContext
        Configuration.getInstance().load(ctx, androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx))
        // Standard browser UA to avoid being blocked by OSM servers
        Configuration.getInstance().userAgentValue = "Mozilla/5.0 (Android; Mobile; rv:123.0) Gecko/123.0 Firefox/123.0"
        // Set internal cache directory to avoid permission issues
        Configuration.getInstance().osmdroidTileCache = java.io.File(ctx.cacheDir, "osmdroid")

        // Get API Key and URL from settings
        val sharedPreferences = getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
        val apiKey = sharedPreferences.getString("api_key", "") ?: ""
        val serverUrl = sharedPreferences.getString("server_url", "") ?: ""

        // Set Authorization header for tile requests
        if (apiKey.isNotEmpty()) {
            Configuration.getInstance().additionalHttpRequestProperties["Authorization"] = "Bearer $apiKey"
        }

        setContentView(R.layout.activity_map)

        // Handle window insets
        // Handle window insets
        val rootView = findViewById<View>(R.id.rootLayout)
        val headerView = findViewById<View>(R.id.headerLayout)
        val bottomInfoLayout = findViewById<View>(R.id.bottomInfoLayout)
        
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            headerView.updatePadding(top = insets.top + 20)
            // Apply bottom padding to the info layout so content sits above nav bar
            bottomInfoLayout.updatePadding(bottom = insets.bottom + 16) // +16 for extra breathing room
            WindowInsetsCompat.CONSUMED
        }

        findViewById<View>(R.id.settingsButton).setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
            safeNoAnimation()
        }

        map = findViewById(R.id.map)
        map.zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
        map.setMultiTouchControls(true)

        // val placeDetailsCard = findViewById<androidx.cardview.widget.CardView>(R.id.placeDetailsCard) // Removed
        val placeName = findViewById<android.widget.TextView>(R.id.placeName)
        val placeDescription = findViewById<android.widget.TextView>(R.id.placeDescription)
        val viewInListButton = findViewById<android.widget.Button>(R.id.viewInListButton)
        val editPlaceButton = findViewById<android.widget.Button>(R.id.editPlaceButton)
        val navigateButton = findViewById<android.widget.Button>(R.id.navigateButton)
        
        var selectedFeature: Feature? = null
        val editLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                // Refresh map content or just close and let main refresh
                setResult(RESULT_OK)
                finish()
            }
        }
        
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
                val serverUrl = sharedPreferences.getString("server_url", "") ?: ""
                val apiKey = sharedPreferences.getString("api_key", "") ?: ""
                NavigationHelper.navigateToPlace(this, feature, apiKey, serverUrl)
            }
        }
        
        viewInListButton.setOnClickListener {
            selectedFeature?.properties?.database_id?.let { id ->
                val resultIntent = android.content.Intent()
                resultIntent.putExtra("selected_id", id)
                setResult(RESULT_OK, resultIntent)
                finish()
                safeNoAnimation()
            }
        }
        
        // Hide card when clicking on map (if possible to detect) or just rely on selection
        // For a simple implementation, tapping the map doesn't inherently clear selection in osmdroid without an overlay.
        // We will just let the user tap another marker or use the button.

        // Set standard OSM tile source
        map.setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
        map.invalidate()

        // Get features from intent handling deprecation
        features = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra("features", Feature::class.java) ?: ArrayList()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra("features") ?: ArrayList()
        }

        // Add markers
        if (features.isNotEmpty()) {
            var minLat = 90.0
            var maxLat = -90.0
            var minLon = 180.0
            var maxLon = -180.0
            var hasValidPoints = false

            // Use BitmapDrawable for cleaner rendering of vector assets in OSMDroid
            // Create shared instances outside the loop for performance
            val defaultIcon = getBitmapDrawable(R.drawable.ic_marker_default)
            val selectedIcon = getBitmapDrawable(R.drawable.ic_marker_selected)
            val markerByDatabaseId = mutableMapOf<Int, Pair<Marker, Feature>>()

            fun selectMarkerAndUpdateUi(m: Marker, f: Feature) {
                lastSelectedMarker?.icon = defaultIcon
                m.icon = selectedIcon
                lastSelectedMarker = m
                map.invalidate()
                placeName.text = f.properties.name ?: "Unknown Place"
                placeDescription.text = f.properties.description ?: ""
                selectedFeature = f
                val dbId = f.properties.database_id
                viewInListButton.isEnabled = dbId != null
                viewInListButton.alpha = if (dbId != null) 1.0f else 0.5f
                editPlaceButton.isEnabled = dbId != null
                editPlaceButton.alpha = if (dbId != null) 1.0f else 0.5f
                navigateButton.isEnabled = dbId != null
                navigateButton.alpha = if (dbId != null) 1.0f else 0.5f
            }

            for (feature in features) {
                val coords = feature.geometry.coordinates
                if (coords.size >= 2) {
                    val lon = coords[0] // GeoJSON is [lon, lat]
                    val lat = coords[1]
                    
                    val point = GeoPoint(lat, lon)
                    val marker = Marker(map)
                    marker.position = point
                    marker.icon = defaultIcon
                    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    marker.infoWindow = null // Disable bubble logic
                    feature.properties.database_id?.let { id -> markerByDatabaseId[id] = Pair(marker, feature) }
                    
                    // Store data directly
                    val name = feature.properties.name ?: "Unknown Place"
                    val desc = feature.properties.description ?: ""
                    val dbId = feature.properties.database_id
                    
                    marker.setOnMarkerClickListener { m, _ ->
                        map.post { selectMarkerAndUpdateUi(m as Marker, feature) }
                        true
                    }
                    
                    map.overlays.add(marker)

                    if (lat < minLat) minLat = lat
                    if (lat > maxLat) maxLat = lat
                    if (lon < minLon) minLon = lon
                    if (lon > maxLon) maxLon = lon
                    hasValidPoints = true
                }
            }

            if (hasValidPoints) {
                 // Zoom to bounds
                 // Fix invalid bounds if all points are the same
                 if (minLat == maxLat) {
                     minLat -= 0.01
                     maxLat += 0.01
                 }
                 if (minLon == maxLon) {
                     minLon -= 0.01
                     maxLon += 0.01
                 }

                 // Expand bounds by a fraction so icons aren't flush against the edge
                 val latPadding = (maxLat - minLat) * 0.15
                 val lonPadding = (maxLon - minLon) * 0.15
                 val paddedMinLat = minLat - latPadding
                 val paddedMaxLat = maxLat + latPadding
                 val paddedMinLon = minLon - lonPadding
                 val paddedMaxLon = maxLon + lonPadding

                 val boundingBox = BoundingBox(paddedMaxLat, paddedMaxLon, paddedMinLat, paddedMinLon)
                 val zoomToId = intent.getIntExtra("zoom_to_id", -1)

                 // setCenter() needs map width/height; run after layout (post() can run before onLayout)
                 fun applyZoomAndCenter() {
                     if (intent.hasExtra("zoom_to_lat") && intent.hasExtra("zoom_to_lon")) {
                         val centerLat: Double
                         val centerLon: Double
                         if (zoomToId >= 0) {
                             val pair = markerByDatabaseId[zoomToId]
                             if (pair != null) {
                                 val (m, f) = pair
                                 val c = f.geometry.coordinates
                                 if (c.size >= 2) {
                                     centerLon = c[0] // GeoJSON: [lon, lat]
                                     centerLat = c[1]
                                     map.controller.setZoom(16.0)
                                     map.controller.setCenter(GeoPoint(centerLat, centerLon))
                                     selectMarkerAndUpdateUi(m, f)
                                 } else {
                                     centerLat = intent.getDoubleExtra("zoom_to_lat", 0.0)
                                     centerLon = intent.getDoubleExtra("zoom_to_lon", 0.0)
                                     map.controller.setZoom(16.0)
                                     map.controller.setCenter(GeoPoint(centerLat, centerLon))
                                 }
                             } else {
                                 centerLat = intent.getDoubleExtra("zoom_to_lat", 0.0)
                                 centerLon = intent.getDoubleExtra("zoom_to_lon", 0.0)
                                 map.controller.setZoom(16.0)
                                 map.controller.setCenter(GeoPoint(centerLat, centerLon))
                             }
                         } else {
                             centerLat = intent.getDoubleExtra("zoom_to_lat", 0.0)
                             centerLon = intent.getDoubleExtra("zoom_to_lon", 0.0)
                             map.controller.setZoom(16.0)
                             map.controller.setCenter(GeoPoint(centerLat, centerLon))
                         }
                     } else {
                         map.zoomToBoundingBox(boundingBox, true)
                     }
                 }

                 map.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                     override fun onGlobalLayout() {
                         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                             map.viewTreeObserver.removeOnGlobalLayoutListener(this)
                         } else {
                             @Suppress("DEPRECATION")
                             map.viewTreeObserver.removeGlobalOnLayoutListener(this)
                         }
                         applyZoomAndCenter()
                     }
                 })
            } else {
                map.controller.setZoom(2.0)
                map.controller.setCenter(GeoPoint(0.0, 0.0))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
    }

    override fun finish() {
        super.finish()
        safeNoAnimation()
    }

    private fun safeNoAnimation() {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun getBitmapDrawable(resId: Int): android.graphics.drawable.BitmapDrawable? {
        val drawable = androidx.core.content.ContextCompat.getDrawable(this, resId) ?: return null
        val bitmap = android.graphics.Bitmap.createBitmap(
            drawable.intrinsicWidth,
            drawable.intrinsicHeight,
            android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return android.graphics.drawable.BitmapDrawable(resources, bitmap)
    }
}
