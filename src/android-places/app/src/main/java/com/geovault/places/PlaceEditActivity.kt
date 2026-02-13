package com.geovault.places

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class PlaceEditActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var nameInput: EditText
    private lateinit var descriptionInput: EditText
    private lateinit var latInput: EditText
    private lateinit var lonInput: EditText
    private lateinit var saveButton: MaterialButton
    private lateinit var titleText: TextView
    
    private var marker: Marker? = null
    private var editFeature: Feature? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // OSMDroid config
        val ctx = applicationContext
        Configuration.getInstance().load(ctx, androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx))
        // Standard browser UA to avoid being blocked by OSM servers
        Configuration.getInstance().userAgentValue = "Mozilla/5.0 (Android; Mobile; rv:123.0) Gecko/123.0 Firefox/123.0"
        // Set internal cache directory to avoid permission issues
        Configuration.getInstance().osmdroidTileCache = java.io.File(ctx.cacheDir, "osmdroid")

        setContentView(R.layout.activity_place_edit)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        initViews()
        setupWindowInsets()
        setupMap()
        
        // Check for edit intent
        editFeature = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra("feature", Feature::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("feature")
        }
        if (editFeature != null) {
            populateFields(editFeature!!)
            titleText.text = "Edit Place"
        }

        setupListeners()
        validateForm() // Initial check
    }

    private fun initViews() {
        map = findViewById(R.id.map)
        nameInput = findViewById(R.id.placeNameInput)
        descriptionInput = findViewById(R.id.placeDescriptionInput)
        latInput = findViewById(R.id.latitudeInput)
        lonInput = findViewById(R.id.longitudeInput)
        saveButton = findViewById(R.id.saveButton)
        titleText = findViewById(R.id.titleText)

        findViewById<View>(R.id.closeButton).setOnClickListener { finish() }
        findViewById<View>(R.id.cancelButton).setOnClickListener { finish() }
    }

    private fun setupWindowInsets() {
        val rootView = findViewById<View>(R.id.rootLayout)
        val headerView = findViewById<View>(R.id.headerLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            
            headerView.updatePadding(top = systemBars.top + 20)
            
            // Apply the larger of the two bottom insets (navigation bar or keyboard)
            val bottomInset = if (ime.bottom > systemBars.bottom) ime.bottom else systemBars.bottom
            view.updatePadding(bottom = bottomInset)
            
            windowInsets
        }
    }

    private fun setupMap() {
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)

        val serverUrl = prefs.getString("server_url", "") ?: ""
        val apiKey = prefs.getString("api_key", "") ?: ""

        // Set standard OSM tile source
        map.setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
        map.invalidate()

        // Click listener for map
        val eventsReceiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                updateCoords(p.latitude, p.longitude)
                return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        }
        map.overlays.add(MapEventsOverlay(eventsReceiver))

        // Initial view
        map.controller.setZoom(2.0)
        map.controller.setCenter(GeoPoint(0.0, 0.0))
    }

    private fun setupListeners() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateMarkerFromInputs()
                validateForm()
            }
        }
        nameInput.addTextChangedListener(watcher)
        latInput.addTextChangedListener(watcher)
        lonInput.addTextChangedListener(watcher)
        // Use the ID directly or cast to View if needed, but MaterialButton is a Button
        findViewById<View>(R.id.useMyLocationButton).setOnClickListener {
            checkLocationPermissionAndGet()
        }

        saveButton.setOnClickListener {
            savePlace()
        }
    }

    private fun populateFields(feature: Feature) {
        nameInput.setText(feature.properties.name)
        descriptionInput.setText(feature.properties.description)
        val coords = feature.geometry.coordinates
        if (coords.size >= 2) {
            updateCoords(coords[1], coords[0])
            map.post {
                map.controller.setZoom(14.0)
                map.controller.setCenter(GeoPoint(coords[1], coords[0]))
            }
        }
    }

    private fun updateCoords(lat: Double, lon: Double) {
        latInput.setText(String.format("%.6f", lat))
        lonInput.setText(String.format("%.6f", lon))
        updateMarker(lat, lon)
        validateForm()
    }

    private fun updateMarkerFromInputs() {
        val latStr = latInput.text.toString()
        val lonStr = lonInput.text.toString()
        if (latStr.isNotEmpty() && lonStr.isNotEmpty()) {
            try {
                val lat = latStr.toDouble()
                val lon = lonStr.toDouble()
                updateMarker(lat, lon)
            } catch (e: Exception) {}
        }
    }

    private fun updateMarker(lat: Double, lon: Double) {
        if (marker == null) {
            marker = Marker(map)
            marker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            marker?.icon = ContextCompat.getDrawable(this, R.drawable.ic_marker_default)
            map.overlays.add(marker)
        }
        marker?.position = GeoPoint(lat, lon)
        map.invalidate()
    }

    private fun checkLocationPermissionAndGet() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100)
        } else {
            getCurrentLocation()
        }
    }

    private fun getCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        
        val cts = CancellationTokenSource()
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
            .addOnSuccessListener { location: Location? ->
                location?.let {
                    updateCoords(it.latitude, it.longitude)
                    map.controller.animateTo(GeoPoint(it.latitude, it.longitude))
                    map.controller.setZoom(15.0)
                } ?: run {
                    // Fallback to last location if fresh one fails
                    fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                        lastLoc?.let {
                            updateCoords(it.latitude, it.longitude)
                            map.controller.animateTo(GeoPoint(it.latitude, it.longitude))
                            map.controller.setZoom(15.0)
                        } ?: run {
                            Toast.makeText(this, "Could not get location", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Location request failed: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun validateForm() {
        val name = nameInput.text.toString().trim()
        val lat = latInput.text.toString().trim()
        val lon = lonInput.text.toString().trim()
        
        val isValid = name.isNotEmpty() && lat.isNotEmpty() && lon.isNotEmpty()
        saveButton.isEnabled = isValid
        // Visual feedback for disabled state
        saveButton.alpha = if (isValid) 1.0f else 0.5f
    }

    private fun savePlace() {
        val name = nameInput.text.toString().trim()
        val latStr = latInput.text.toString()
        val lonStr = lonInput.text.toString()

        if (name.isEmpty()) {
            nameInput.error = "Name is required"
            return
        }
        if (latStr.isEmpty() || lonStr.isEmpty()) {
            Toast.makeText(this, "Coordinates are required", Toast.LENGTH_SHORT).show()
            return
        }

        val lat = latStr.toDouble()
        val lon = lonStr.toDouble()

        val feature = Feature(
            type = "Feature",
            geometry = Geometry(type = "Point", coordinates = listOf(lon, lat)),
            properties = Properties(
                database_id = editFeature?.properties?.database_id,
                name = name,
                description = descriptionInput.text.toString().trim(),
                created_at = editFeature?.properties?.created_at
            )
        )

        val serverUrl = prefs.getString("server_url", "") ?: ""
        val apiKey = prefs.getString("api_key", "") ?: ""
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val api = RetrofitClient.getClient(baseUrl, apiKey).create(GeovaultApi::class.java)

        saveButton.isEnabled = false
        val call = if (editFeature != null) {
            api.updatePlace(editFeature!!.properties.database_id!!, feature)
        } else {
            api.createPlace(feature)
        }

        call.enqueue(object : Callback<Feature> {
            override fun onResponse(call: Call<Feature>, response: Response<Feature>) {
                saveButton.isEnabled = true
                if (response.isSuccessful) {
                    Toast.makeText(this@PlaceEditActivity, "Place saved", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                } else {
                    Toast.makeText(this@PlaceEditActivity, "Error: ${response.code()}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<Feature>, t: Throwable) {
                saveButton.isEnabled = true
                Toast.makeText(this@PlaceEditActivity, "Failed: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                getCurrentLocation()
            } else {
                Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
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
}
