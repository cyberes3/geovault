package com.geovault.tracker.fragments

import android.graphics.Color
import android.content.*
import android.location.Location
import android.os.Bundle
import android.view.Choreographer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.fragment.app.Fragment
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.map.MapLibreManager
import com.geovault.common.map.MapMarkerUtils
import com.geovault.tracker.MainActivity
import com.geovault.tracker.R
import com.geovault.tracker.TrackerRepository
import com.geovault.tracker.TrackingService
import kotlinx.coroutines.*
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.plugins.annotation.LineManager
import org.maplibre.android.plugins.annotation.LineOptions
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.android.plugins.annotation.SymbolOptions

class MapFragment : Fragment() {

    private lateinit var mapView: MapView
    private lateinit var mapManager: MapLibreManager
    private var maplibreMap: MapLibreMap? = null
    private var symbolManager: SymbolManager? = null
    private var lineManager: LineManager? = null
    private var trackPoints: MutableList<LatLng> = mutableListOf()
    /** Tracker color (hex e.g. "#3388ff") for trail and icon; set when loading tracker in fetchHistory. */
    private var currentTrackerColor: String? = null

    private lateinit var mapLoadingOverlay: View
    private lateinit var trackerLabelCard: View
    private lateinit var trackerNameLabel: TextView
    private lateinit var resetToTrackerButton: View
    private lateinit var mapToggle: View
    private lateinit var zoomToLatestButton: View
    private lateinit var zoomToLatestButtonIcon: ImageView

    private var mapReady = false
    private var followLockEnabled = false
    /** When true, fetchHistory() will zoom the camera to fit the loaded track (e.g. after "View on map"). */
    private var zoomToTrackAfterLoad = false
    /** When true, fetchHistory() will not move the camera (restore track only). */
    private var restoreOnlyNoZoom = false

    private val mainScope = CoroutineScope(Dispatchers.Main + Job())

    private val failLoadingMapListener = MapView.OnDidFailLoadingMapListener { errorMessage ->
        Log.e(TAG, "Map style load failed: $errorMessage")
        val map = maplibreMap ?: return@OnDidFailLoadingMapListener
        val effectiveId = mapManager.sourceManager.getEffectiveSourceId()
        if (mapManager.sourceManager.isVectorSource(effectiveId)) {
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                Toast.makeText(requireContext(), getString(R.string.map_style_unavailable_fallback_osm), Toast.LENGTH_SHORT).show()
                mapManager.loadOsmFallback(map)
            }
        } else {
            requireActivity().runOnUiThread {
                if (isAdded) Toast.makeText(requireContext(), "Map failed: $errorMessage", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val location = IntentCompat.getParcelableExtra(intent, "location", Location::class.java)
            if (location != null) {
                updateLocationOnMap(location)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_map, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mapView = view.findViewById(R.id.mapView)
        mapLoadingOverlay = view.findViewById(R.id.mapLoadingOverlay)
        trackerLabelCard = view.findViewById(R.id.trackerLabelCard)
        trackerNameLabel = view.findViewById(R.id.trackerNameLabel)
        resetToTrackerButton = view.findViewById(R.id.resetToTrackerButton)
        mapToggle = view.findViewById(R.id.mapToggle)
        zoomToLatestButton = view.findViewById(R.id.zoomToLatestButton)
        zoomToLatestButtonIcon = view.findViewById(R.id.zoomToLatestButtonIcon)

        updateTrackerLabel()
        resetToTrackerButton.setOnClickListener {
            restoreTrackForSelectedTracker()
        }

        followLockEnabled = savedInstanceState?.getBoolean(KEY_FOLLOW_LOCK, false) ?: false
        updateFollowLockButton()
        updateZoomToLatestButtonState()

        mapManager = MapLibreManager(requireActivity(), mapView)
        mapManager.onStyleLoaded = { map, style ->
            maplibreMap = map
            mapManager.addMarkerIcon(style, "marker-default", R.drawable.ic_marker_default)
            MapMarkerUtils.getMarkerBitmapWithTintedForeground(
                requireContext(),
                R.drawable.ic_track_direction_arrow_circle,
                R.drawable.ic_track_direction_arrow_chevron_fill,
                R.drawable.ic_track_direction_arrow_chevron_stroke,
                Color.parseColor("#3388ff")
            )?.let { bitmap ->
                style.addImage("track-direction-arrow", bitmap)
            }
            lineManager = LineManager(mapView, map, style)
            symbolManager = SymbolManager(mapView, map, style)
            mapReady = true
            mapLoadingOverlay.visibility = View.GONE
            mapView.post { fetchHistory() }
        }
        
        mapView.onCreate(savedInstanceState)
        mapView.addOnDidFailLoadingMapListener(failLoadingMapListener)

        mapManager.fetchMapSources {
            maplibreMap?.let { map ->
                Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        if (!isAdded) return
                        mapManager.applySelectedSource(map)
                    }
                })
            }
        }
        
        mapView.getMapAsync { map ->
            maplibreMap = map
            mapManager.setupBaseMapSettings(map)
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == 1 && followLockEnabled) {
                    followLockEnabled = false
                    updateFollowLockButton()
                }
            }
            val serverUrl = GeovaultAuthManager.getServerUrl(requireContext())
            if (mapManager.sourcesFetched || serverUrl.isEmpty()) {
                Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        if (!isAdded) return
                        mapManager.applySelectedSource(map)
                    }
                })
            }
        }

        mapToggle.setOnClickListener {
            val map = maplibreMap ?: return@setOnClickListener
            mapManager.sourceManager.setSelectedSourceId(mapManager.sourceManager.getNextSourceId())
            mapManager.applySelectedSource(map)
        }
        
        zoomToLatestButton.setOnClickListener {
            followLockEnabled = !followLockEnabled
            if (followLockEnabled && trackPoints.isNotEmpty()) {
                maplibreMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(trackPoints.last(), 16.0))
            }
            updateFollowLockButton()
        }
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        updateTrackerLabel()

        ContextCompat.registerReceiver(
            requireContext(),
            locationReceiver,
            IntentFilter("com.geovault.tracker.LOCATION_UPDATE"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        
        try {
            requireContext().unregisterReceiver(locationReceiver)
        } catch (e: IllegalArgumentException) {
            // Already unregistered
        }
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
    }

    override fun onDestroyView() {
        mapView.removeOnDidFailLoadingMapListener(failLoadingMapListener)
        super.onDestroyView()
        mapView.onDestroy()
        mainScope.cancel()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_FOLLOW_LOCK, followLockEnabled)
        mapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    private fun updateFollowLockButton() {
        if (followLockEnabled) {
            zoomToLatestButtonIcon.setImageResource(R.drawable.ic_crosshair_locked)
            zoomToLatestButtonIcon.contentDescription = getString(R.string.follow_lock_on_description)
        } else {
            zoomToLatestButtonIcon.setImageResource(R.drawable.ic_crosshair)
            zoomToLatestButtonIcon.contentDescription = getString(R.string.zoom_to_latest_description)
        }
    }

    private fun updateZoomToLatestButtonState() {
        val hasTrack = trackPoints.isNotEmpty()
        zoomToLatestButton.isEnabled = hasTrack
        zoomToLatestButton.alpha = if (hasTrack) 1f else 0.4f
    }

    private fun updateTrackerLabel() {
        val prefs = requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
        val trackerId = prefs.getString("selected_tracker_id", "") ?: ""
        val trackerName = prefs.getString("selected_tracker_name", "") ?: ""
        if (trackerId.isEmpty()) {
            trackerLabelCard.visibility = View.GONE
        } else {
            trackerLabelCard.visibility = View.VISIBLE
            trackerNameLabel.text = trackerName.ifEmpty { getString(R.string.select_tracker) }
            // Show reset only when we're not viewing the selected tracker's track (no track loaded yet)
            resetToTrackerButton.visibility = if (trackPoints.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun updateLocationOnMap(location: Location) {
        val latLng = LatLng(location.latitude, location.longitude)
        trackPoints.add(latLng)
        if (trackPoints.size > 1000) {
            trackPoints.removeAt(0)
        }
        val map = maplibreMap
        if (map != null) {
            updateTrackLine()
            updateZoomToLatestButtonState()
            if (followLockEnabled) {
                map.moveCamera(CameraUpdateFactory.newLatLng(latLng))
            }
        }
    }

    /**
     * Clear the map track and refetch only the currently selected tracker.
     * Call this when switching to the map from "View on map" so only that tracker is shown.
     */
    fun refreshTrackForSelectedTracker() {
        trackPoints.clear()
        updateTrackLine()
        updatePositionSymbol()
        zoomToTrackAfterLoad = true
        fetchHistory()
    }

    /**
     * Refetch and redraw the selected tracker's track without moving the camera.
     */
    private fun restoreTrackForSelectedTracker() {
        trackPoints.clear()
        updateTrackLine()
        updatePositionSymbol()
        restoreOnlyNoZoom = true
        fetchHistory()
    }

    private fun fetchHistory() {
        val prefs = requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
        val trackerId = prefs.getString("selected_tracker_id", "") ?: ""
        if (trackerId.isEmpty()) {
            updateZoomToLatestButtonState()
            updateTrackerLabel()
            return
        }

        TrackerRepository.getTrackerGeometry(requireContext(), trackerId) { tracker ->
            mainScope.launch {
                if (tracker != null) {
                    currentTrackerColor = (tracker.color ?: "#3388ff").let { if (it.startsWith("#")) it else "#$it" }
                }
                val coords = tracker?.geometry?.coordinates
                if (coords != null) {
                    // Don't overwrite with server data while actively tracking; live points are more current
                    if (!TrackingService.isRunning || trackPoints.isEmpty()) {
                        trackPoints.clear()
                        trackPoints.addAll(coords.map { LatLng(it[1], it[0]) }.takeLast(1000))
                    }
                    updateTrackLine()
                    val map = maplibreMap
                    val shouldZoom = zoomToTrackAfterLoad
                    zoomToTrackAfterLoad = false
                    if (shouldZoom && map != null && trackPoints.isNotEmpty()) {
                        if (trackPoints.size >= 2) {
                            val bounds = LatLngBounds.Builder().apply {
                                trackPoints.forEach { include(it) }
                            }.build()
                            val paddingPx = (48 * resources.displayMetrics.density).toInt()
                            map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, paddingPx))
                        } else {
                            map.moveCamera(CameraUpdateFactory.newLatLngZoom(trackPoints.single(), 14.0))
                        }
                    } else if (!restoreOnlyNoZoom && map != null && trackPoints.isNotEmpty()) {
                        map.moveCamera(CameraUpdateFactory.newLatLng(trackPoints.last()))
                    }
                }
                restoreOnlyNoZoom = false
                updateZoomToLatestButtonState()
                updateTrackerLabel()
            }
        }
    }

    private fun updateTrackLine() {
        val manager = lineManager ?: return
        manager.deleteAll()
        val lineColor = currentTrackerColor ?: "#3388ff"
        if (trackPoints.size >= 2) {
            val outlineColorInt = ContextCompat.getColor(requireContext(), R.color.track_line_outline)
            val outlineColor = String.format("#%06X", 0xFFFFFF and outlineColorInt)
            val segments = splitTrackIntoSegments(trackPoints)
            for (segment in segments) {
                // Outline for each segment (black in day, light grey in night)
                manager.create(LineOptions()
                    .withLatLngs(segment)
                    .withLineColor(outlineColor)
                    .withLineWidth(5f)
                )
                manager.create(LineOptions()
                    .withLatLngs(segment)
                    .withLineColor(lineColor)
                    .withLineWidth(3f)
                )
            }
        }
        updatePositionSymbol()
    }

    private fun updatePositionSymbol() {
        val symManager = symbolManager ?: return
        symManager.deleteAll()
        if (trackPoints.isEmpty()) return
        val last = trackPoints.last()
        val rotation = getTrackDirectionDegrees(trackPoints)
        val hexColor = currentTrackerColor ?: "#3388ff"
        val style = maplibreMap?.style ?: return
        val imageId = "track-direction-arrow-${hexColor.replace("#", "")}"
        val tintedBitmap = MapMarkerUtils.getMarkerBitmapWithTintedForeground(
            requireContext(),
            R.drawable.ic_track_direction_arrow_circle,
            R.drawable.ic_track_direction_arrow_chevron_fill,
            R.drawable.ic_track_direction_arrow_chevron_stroke,
            Color.parseColor(hexColor)
        )
        val symbolIconId = if (tintedBitmap != null) {
            try {
                style.addImage(imageId, tintedBitmap)
            } catch (_: Exception) { /* id may already exist */ }
            imageId
        } else {
            "track-direction-arrow"
        }
        symManager.create(SymbolOptions()
            .withLatLng(last)
            .withIconImage(symbolIconId)
            .withIconSize(0.75f)
            .withIconRotate(rotation)
        )
    }

    private fun getTrackDirectionDegrees(points: List<LatLng>): Float {
        if (points.size < 2) return 0f
        val prev = points[points.size - 2]
        val last = points.last()
        val dLon = last.longitude - prev.longitude
        val dLat = last.latitude - prev.latitude
        if (dLon == 0.0 && dLat == 0.0) return 0f
        return (Math.atan2(dLon, dLat) * 180 / Math.PI).toFloat()
    }

    /**
     * Split track points into segments so that no segment spans more than MAX_JUMP_METERS.
     * Consecutive points farther apart than that start a new segment (the jump is not drawn).
     */
    private fun splitTrackIntoSegments(points: List<LatLng>): List<List<LatLng>> {
        if (points.size < 2) return emptyList()
        val segments = mutableListOf<MutableList<LatLng>>()
        var current = mutableListOf(points[0])
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            val results = FloatArray(3)
            Location.distanceBetween(prev.latitude, prev.longitude, curr.latitude, curr.longitude, results)
            val distanceMeters = results[2]
            if (distanceMeters > MAX_JUMP_METERS) {
                if (current.size >= 2) segments.add(current)
                current = mutableListOf(curr)
            } else {
                current.add(curr)
            }
        }
        if (current.size >= 2) segments.add(current)
        return segments
    }

    companion object {
        private const val TAG = "MapFragment"
        private const val KEY_FOLLOW_LOCK = "follow_lock"
        /** Do not draw track across jumps larger than this (meters). 100 miles. */
        private const val MAX_JUMP_METERS = 100f * 1609.344f
    }
}
