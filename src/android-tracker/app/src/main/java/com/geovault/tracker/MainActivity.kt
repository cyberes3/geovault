package com.geovault.tracker

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Choreographer
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.ServerUrlContract
import com.geovault.common.map.MapLibreManager
import com.geovault.tracker.db.AppDatabase
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.android.plugins.annotation.SymbolOptions
import org.maplibre.android.plugins.annotation.LineManager
import org.maplibre.android.plugins.annotation.LineOptions
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class MainActivity : AppCompatActivity() {

    private var isGuestView: Boolean = false

    private lateinit var mapView: MapView
    private lateinit var mapManager: MapLibreManager
    private var maplibreMap: MapLibreMap? = null
    private var symbolManager: SymbolManager? = null
    private var lineManager: LineManager? = null
    private var trackPoints: MutableList<LatLng> = mutableListOf()
    
    private lateinit var statusView: View
    private lateinit var trackingStatusText: TextView
    private lateinit var queueCountText: TextView
    private lateinit var sessionStatsContainer: View
    private lateinit var trackingDurationText: TextView
    private lateinit var lastPointSentText: TextView
    private lateinit var pointsSentSessionText: TextView
    private lateinit var startStopButton: MaterialButton
    private lateinit var currentLocationText: TextView
    private lateinit var mapLoadingOverlay: View

    /** True after map style has loaded (onStyleLoaded ran). Used to hide loading overlay and avoid nav lag. */
    private var mapReady = false

    /** True when Map tab is shown (statusView hidden). Used for camera updates. */
    private var isMapTabVisible = false

    private val mainScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var database: AppDatabase
    private val sessionStatsHandler = Handler(Looper.getMainLooper())
    private val sessionStatsTickerIntervalMs = 1000L

    private val sessionStatsTicker = object : Runnable {
        override fun run() {
            if (!TrackingService.isRunning) return
            updateSessionStats()
            updateQueueCount()
            sessionStatsHandler.postDelayed(this, sessionStatsTickerIntervalMs)
        }
    }

    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val location = IntentCompat.getParcelableExtra(intent, "location", Location::class.java)
            if (location != null) {
                updateLocationOnMap(location)
                currentLocationText.text = "Last point: ${String.format("%.6f", location.latitude)}, ${String.format("%.6f", location.longitude)}"
            }
            updateQueueCount()
            updateSessionStats()
        }
    }

    private val sessionStatsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateTrackingUi()
            updateSessionStats()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        
        if (fineGranted || coarseGranted) {
            checkBackgroundLocation()
        } else {
            Toast.makeText(this, "Location permission is required for tracking", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == TrackingService.ACTION_STOP) {
            startService(Intent(this, TrackingService::class.java).apply { action = TrackingService.ACTION_STOP })
        }
        if (!GeovaultAuthManager.isLoggedIn(this)) {
            isGuestView = true
            setContentView(R.layout.activity_main_guest)
            setupGuestView()
            return
        }
        setContentView(R.layout.activity_main)
        setupMainContent(savedInstanceState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == TrackingService.ACTION_STOP && !isGuestView) {
            startService(Intent(this, TrackingService::class.java).apply { action = TrackingService.ACTION_STOP })
            statusView.postDelayed({ updateTrackingUi() }, 200)
        }
    }

    private fun normalizeServerUrl(url: String): String {
        var serverUrl = url.trim().trimStart('/').trimEnd('/')
        if (serverUrl.isNotEmpty() && !serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            serverUrl = "https://$serverUrl"
        }
        return serverUrl
    }

    private fun setupGuestView() {
        val rootView = findViewById<View>(R.id.rootLayout)
        val headerLayout = findViewById<View>(R.id.headerLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            headerLayout.updatePadding(top = insets.top + 20)
            view.updatePadding(bottom = insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
        val serverUrlEdit = findViewById<EditText>(R.id.guestServerUrlEdit)
        val serverUrl = GeovaultAuthManager.getServerUrl(this)
        if (serverUrl.isNotEmpty()) {
            serverUrlEdit.setText(serverUrl)
        } else {
            val otherUrls = ServerUrlContract.getServerUrlsFromOtherApps(this)
            if (otherUrls.size == 1) {
                serverUrlEdit.setText(otherUrls.single())
            }
        }
        findViewById<MaterialButton>(R.id.guestConnectButton).setOnClickListener {
            val url = normalizeServerUrl(serverUrlEdit.text.toString())
            if (url.isEmpty()) {
                Toast.makeText(this, "Please enter server URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            GeovaultAuthManager.setServerUrl(this, url)
            val (verifier, challenge) = GeovaultAuthManager.generatePkcePair()
            val state = java.util.UUID.randomUUID().toString()
            GeovaultAuthManager.savePkceState(this, verifier, state)
            val authUrl = GeovaultAuthManager.buildAuthorizeUrl(url, challenge, state)
            GeovaultAuthManager.launchOAuthInBrowser(this, authUrl)
        }
    }

    private fun setupMainContent(savedInstanceState: Bundle?) {
        val rootView = findViewById<View>(R.id.rootLayout)
        val headerLayout = findViewById<View>(R.id.headerLayout)
        val mainContentLayout = findViewById<View>(R.id.mainContentLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            headerLayout.updatePadding(top = insets.top + 20)
            mainContentLayout.updatePadding(bottom = insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(rootView)

        database = AppDatabase.getDatabase(this)
        
        mapView = findViewById(R.id.mapView)
        mapLoadingOverlay = findViewById(R.id.mapLoadingOverlay)
        statusView = findViewById(R.id.statusView)
        trackingStatusText = findViewById(R.id.trackingStatusText)
        queueCountText = findViewById(R.id.queueCountText)
        sessionStatsContainer = findViewById(R.id.sessionStatsContainer)
        trackingDurationText = findViewById(R.id.trackingDurationText)
        lastPointSentText = findViewById(R.id.lastPointSentText)
        pointsSentSessionText = findViewById(R.id.pointsSentSessionText)
        startStopButton = findViewById(R.id.startStopButton)
        currentLocationText = findViewById(R.id.currentLocationText)

        mapManager = MapLibreManager(this, mapView)
        mapManager.onStyleLoaded = { map, style ->
            maplibreMap = map
            mapManager.addMarkerIcon(style, "marker-default", R.drawable.ic_marker_default)
            mapManager.addMarkerIcon(style, "track-direction-arrow", R.drawable.ic_track_direction_arrow)
            lineManager = LineManager(mapView, map, style)
            symbolManager = SymbolManager(mapView, map, style)
            mapReady = true
            mapLoadingOverlay.visibility = View.GONE
            mapView.post { fetchHistory() }
        }
        mapView.onCreate(savedInstanceState)
        mapManager.fetchMapSources {
            maplibreMap?.let { map ->
                Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        if (!isDestroyed) mapManager.applySelectedSource(map)
                    }
                })
            }
        }
        mapView.getMapAsync { map ->
            maplibreMap = map
            mapManager.setupBaseMapSettings(map)
            val serverUrl = GeovaultAuthManager.getServerUrl(this)
            if (mapManager.sourcesFetched || serverUrl.isEmpty()) {
                Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        if (!isDestroyed) mapManager.applySelectedSource(map)
                    }
                })
            }
        }

        startStopButton.setOnClickListener { toggleTracking() }

        findViewById<View>(R.id.navHome).setOnClickListener { showStatus() }
        findViewById<View>(R.id.navMap).setOnClickListener { showMap() }
        findViewById<View>(R.id.navSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.mapToggle).setOnClickListener {
            val map = maplibreMap ?: return@setOnClickListener
            mapManager.sourceManager.setSelectedSourceId(mapManager.sourceManager.getNextSourceId())
            mapManager.applySelectedSource(map)
        }
        findViewById<View>(R.id.zoomToLatestButton).setOnClickListener {
            if (trackPoints.isNotEmpty()) {
                maplibreMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(trackPoints.last(), 16.0))
            }
        }

        // Show Home on fresh launch; restore Map tab when activity was recreated (e.g. rotation, process death)
        if (savedInstanceState?.getBoolean(KEY_MAP_TAB_VISIBLE, false) == true) {
            showMap()
        } else {
            showStatus()
        }
        updateTrackingUi()
        updateQueueCount()

        requestPermissions()
        checkBatteryOptimization()
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        )
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun checkBackgroundLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionsLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }
    
    private val requestPermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    private fun checkBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun toggleTracking() {
        val intent = Intent(this, TrackingService::class.java)
        if (TrackingService.isRunning) {
            intent.action = TrackingService.ACTION_STOP
            startService(intent)
            statusView.postDelayed({ updateTrackingUi() }, 200)
        } else {
            val secret = getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE).getString("tracker_secret", "")
            if (secret.isNullOrEmpty()) {
                Toast.makeText(this, "Please select a tracker in settings first", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, SettingsActivity::class.java))
                return
            }
            intent.action = TrackingService.ACTION_START
            startForegroundService(intent)
            if (trackPoints.isNotEmpty()) {
                maplibreMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(trackPoints.last(), 16.0))
            }
            // Service sets isRunning asynchronously; refresh UI and start ticker after it has started
            statusView.postDelayed({
                updateTrackingUi()
                if (TrackingService.isRunning) {
                    sessionStatsHandler.removeCallbacks(sessionStatsTicker)
                    sessionStatsHandler.post(sessionStatsTicker)
                }
            }, 400)
        }
        updateTrackingUi()
    }

    private fun updateTrackingUi() {
        val running = TrackingService.isRunning
        trackingStatusText.text = if (running) getString(R.string.tracking_active) else getString(R.string.not_tracking)
        trackingStatusText.setTextColor(if (running) ContextCompat.getColor(this, com.geovault.common.R.color.gv_common_spinner_blue) else ContextCompat.getColor(this, R.color.text_primary))
        startStopButton.text = if (running) getString(R.string.stop_tracking) else getString(R.string.start_tracking)
        updateSessionStats()
    }

    private fun formatDurationMs(ms: Long): String {
        val totalSec = (ms / 1000).toInt().coerceAtLeast(0)
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }

    private fun formatTimeAgo(epochMs: Long): String {
        if (epochMs <= 0) return "now"
        val elapsed = System.currentTimeMillis() - epochMs
        return when {
            elapsed < 10_000 -> "now"
            elapsed < 60_000 -> "${elapsed / 1000}s"
            elapsed < 3600_000 -> "${elapsed / 60_000}m"
            elapsed < 86400_000 -> "${elapsed / 3600_000}h"
            else -> "${elapsed / 86400_000}d"
        }
    }

    private fun updateSessionStats() {
        sessionStatsContainer.visibility = View.VISIBLE
        val running = TrackingService.isRunning
        if (!running) {
            trackingDurationText.text = "—"
            lastPointSentText.text = "—"
            pointsSentSessionText.text = "—"
            return
        }
        val startMs = TrackingService.sessionStartTimeMs
        val durationStr = if (startMs > 0) formatDurationMs(System.currentTimeMillis() - startMs) else "00:00:00"
        trackingDurationText.text = durationStr
        val lastAgo = formatTimeAgo(TrackingService.lastPointSentAtMs)
        lastPointSentText.text = if (lastAgo == "now") lastAgo else "-$lastAgo"
        pointsSentSessionText.text = TrackingService.pointsSentThisSession.toString()
    }

    private fun updateQueueCount() {
        mainScope.launch {
            val count = withContext(Dispatchers.IO) { database.locationDao().getCount() }
            queueCountText.text = count.toString()
        }
    }

    /** Degrees from north (0 = up), clockwise. Same as website getTrackDirectionAngle. */
    private fun getTrackDirectionDegrees(points: List<LatLng>): Float {
        if (points.size < 2) return 0f
        val prev = points[points.size - 2]
        val last = points.last()
        val dLon = last.longitude - prev.longitude
        val dLat = last.latitude - prev.latitude
        if (dLon == 0.0 && dLat == 0.0) return 0f
        return (Math.atan2(dLon, dLat) * 180 / Math.PI).toFloat()
    }

    private fun updateLocationOnMap(location: Location) {
        val map = maplibreMap ?: return
        val latLng = LatLng(location.latitude, location.longitude)
        trackPoints.add(latLng)
        if (trackPoints.size > 1000) {
            trackPoints.removeAt(0)
        }
        updateTrackLine()
        map.animateCamera(CameraUpdateFactory.newLatLng(latLng))
    }

    private fun fetchHistory() {
        val trackerId = getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
            .getString("selected_tracker_id", "") ?: ""
        if (trackerId.isEmpty()) return

        TrackerRepository.getTracker(this, trackerId) { tracker ->
            mainScope.launch {
                val coords = tracker?.geometry?.coordinates
                if (coords != null) {
                    trackPoints.clear()
                    // GeoJson is [lon, lat]
                    trackPoints.addAll(coords.map { LatLng(it[1], it[0]) }.takeLast(1000))
                    updateTrackLine()
                    if (trackPoints.isNotEmpty()) {
                        maplibreMap?.animateCamera(CameraUpdateFactory.newLatLng(trackPoints.last()))
                    }
                }
            }
        }
    }

    private fun updateTrackLine() {
        val manager = lineManager ?: return
        manager.deleteAll()
        if (trackPoints.size >= 2) {
            manager.create(LineOptions()
                .withLatLngs(trackPoints)
                .withLineColor("#3388ff")
                .withLineWidth(3f)
            )
        }
        updatePositionSymbol()
    }

    private fun updatePositionSymbol() {
        symbolManager ?: return
        symbolManager?.deleteAll()
        if (trackPoints.isEmpty()) return
        val last = trackPoints.last()
        val rotation = getTrackDirectionDegrees(trackPoints)
        symbolManager?.create(SymbolOptions()
            .withLatLng(last)
            .withIconImage("track-direction-arrow")
            .withIconSize(0.75f)
            .withIconRotate(rotation)
        )
    }


    private fun showStatus() {
        isMapTabVisible = false
        statusView.visibility = View.VISIBLE
        mapLoadingOverlay.visibility = View.GONE
        findViewById<View>(R.id.mapToggle).visibility = View.GONE
        findViewById<View>(R.id.zoomToLatestButton).visibility = View.GONE
        findViewById<TextView>(R.id.appTitle).text = getString(R.string.live_tracker_title)
    }

    private fun showMap() {
        isMapTabVisible = true
        statusView.visibility = View.GONE
        findViewById<View>(R.id.mapToggle).visibility = View.VISIBLE
        findViewById<View>(R.id.zoomToLatestButton).visibility = View.VISIBLE
        if (!mapReady) {
            mapLoadingOverlay.visibility = View.VISIBLE
            mapLoadingOverlay.bringToFront()
        } else {
            mapLoadingOverlay.visibility = View.GONE
        }
        findViewById<TextView>(R.id.appTitle).text = getString(R.string.live_tracker_title)
    }

    override fun onStart() {
        super.onStart()
        if (isGuestView) {
            if (GeovaultAuthManager.isLoggedIn(this)) {
                isGuestView = false
                setContentView(R.layout.activity_main)
                setupMainContent(null)
            } else {
                return
            }
        }
        mapView.onStart()
        registerReceiver(locationReceiver, IntentFilter("com.geovault.tracker.LOCATION_UPDATE"), ContextCompat.RECEIVER_NOT_EXPORTED)
        registerReceiver(sessionStatsReceiver, IntentFilter(TrackingService.SESSION_STATS_UPDATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        updateTrackingUi()
        updateQueueCount()
        if (TrackingService.isRunning) {
            sessionStatsHandler.removeCallbacks(sessionStatsTicker)
            sessionStatsHandler.post(sessionStatsTicker)
        }
        GeovaultAuthManager.fetchUserStatus(this)
    }

    override fun onResume() {
        super.onResume()
        if (!isGuestView) {
            mapView.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isGuestView) {
            mapView.onPause()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isGuestView) {
            sessionStatsHandler.removeCallbacks(sessionStatsTicker)
            mapView.onStop()
            unregisterReceiver(locationReceiver)
            try { unregisterReceiver(sessionStatsReceiver) } catch (_: IllegalArgumentException) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isGuestView) {
            mapView.onDestroy()
            mainScope.cancel()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!isGuestView) {
            outState.putBoolean(KEY_MAP_TAB_VISIBLE, isMapTabVisible)
            mapView.onSaveInstanceState(outState)
        }
    }

    companion object {
        private const val KEY_MAP_TAB_VISIBLE = "map_tab_visible"
    }
}
