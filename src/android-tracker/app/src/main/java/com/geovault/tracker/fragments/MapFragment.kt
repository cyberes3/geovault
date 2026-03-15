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
import com.geovault.common.LoadingSpinner
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.fragment.app.Fragment
import com.geovault.common.GeovaultAuthManager
import androidx.core.os.bundleOf
import com.geovault.common.map.GeoVaultMapFragment
import com.geovault.common.map.LocationComponentHelper
import com.geovault.common.map.MapLibreManager
import com.geovault.common.map.MapMarkerUtils
import com.geovault.tracker.defaultTrackerColorHex
import com.geovault.tracker.parseHexToColor
import com.geovault.tracker.LiveTrackStreamingService
import com.geovault.tracker.MainActivity
import com.geovault.tracker.Group
import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerRepository
import com.geovault.tracker.TrackingService
import com.geovault.tracker.TrackUpdateHelper
import kotlinx.coroutines.*
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

class MapFragment : Fragment() {

    private var mapFragment: GeoVaultMapFragment? = null
    private var mapManager: MapLibreManager? = null
    private var maplibreMap: MapLibreMap? = null
    private var trackPoints: MutableList<LatLng> = mutableListOf()
    private var trackTimestamps: MutableList<Long> = mutableListOf()
    /** Tracker color (hex, default from R.color.default_tracker_color) for trail and icon; set when loading tracker in fetchHistory. */
    private var currentTrackerColor: String? = null

    private lateinit var mapLoadingOverlay: View
    private lateinit var trackerLabelCard: View
    private lateinit var trackerNameLabel: TextView
    private lateinit var resetToTrackerButton: View
    private lateinit var mapToggle: View
    private lateinit var zoomToLatestButton: View
    private lateinit var zoomToLatestButtonIcon: ImageView
    private lateinit var zoomInButton: View
    private lateinit var zoomOutButton: View
    private lateinit var geometryLoadingSpinner: LoadingSpinner
    private lateinit var lastUpdatedLabel: TextView
    private lateinit var showAllTrackersButton: View

    /** When true, map shows all trackers; when false, single default/displayed tracker. */
    private var showAllTrackers = false

    /** True while fetchFullGeometryAndApply is in progress; used so bottom-right spinner stays visible if streaming starts. */
    private var geometryLoadingInProgress = false

    /** Last streamed point timestamp (ms); only set when viewing a non-default track. Cleared when stopping streaming or switching tracker. */
    private var lastStreamedPointTimeMs: Long? = null
    /** Last streamed point accuracy (m); only set when viewing a non-default track and props.acc is sent. */
    private var lastStreamedAccuracyMeters: Float? = null
    /** Cached last-update time (ms) from loaded tracker/initial data; used to prefill "Updated" chip before first network point. */
    private var lastCachedUpdateTimeMs: Long? = null

    private var mapReady = false
    private var followLockEnabled = false
    private var followLockNeedsInitialZoom = false
    /** When true, fetchHistory() will zoom the camera to fit the loaded track (e.g. after "View on map"). */
    private var zoomToTrackAfterLoad = false
    /** When true, fetchHistory() will not move the camera (restore track only). */
    private var restoreOnlyNoZoom = false
    /** Id of the tracker currently shown on the map; used to show reset when viewing a non-default track. */
    private var displayedTrackerId: String? = null
    /** Name of the tracker currently shown on the map; used for the label in the upper left. */
    private var displayedTrackerName: String? = null
    /** Group currently shown on map, when in group context. */
    private var displayedGroupName: String? = null
    /** Explicit map UI context used for chip/button state. */
    private var mapViewContext: MapViewContext = MapViewContext.DEFAULT_TRACKER
    /** Dirty flag for debounced track line updates. */
    private var trackLineDirty = false

    private val mainScope = CoroutineScope(Dispatchers.Main + Job())

    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val location = IntentCompat.getParcelableExtra(intent, "location", Location::class.java)
            if (location == null) return
            val prefs = context.getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
            val defaultTrackerId = prefs.getString("selected_tracker_id", "") ?: ""
            if (defaultTrackerId.isEmpty() || displayedTrackerId != defaultTrackerId) return
            updateLocationOnMap(location)
        }
    }

    private val liveTrackPointReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != LiveTrackStreamingService.BROADCAST_TRACK_POINT) return
            val trackId = intent.getStringExtra(LiveTrackStreamingService.EXTRA_TRACK_ID) ?: return
            if (trackId != displayedTrackerId) return
            val tsMs = intent.getLongExtra(LiveTrackStreamingService.EXTRA_POINT_TS_MS, 0L)
            if (tsMs > 0L) {
                lastStreamedPointTimeMs = tsMs
                val defaultTrackerId = context.getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
                    .getString("selected_tracker_id", "") ?: ""
                if (isAdded) updateStreamingUi(defaultTrackerId)
            }
            if (intent.hasExtra(LiveTrackStreamingService.EXTRA_ACCURACY_METERS)) {
                lastStreamedAccuracyMeters = intent.getFloatExtra(LiveTrackStreamingService.EXTRA_ACCURACY_METERS, 0f).takeIf { it > 0f }
            }
            val lat = intent.getDoubleExtra(LiveTrackStreamingService.EXTRA_POINT_LAT, 0.0)
            val lon = intent.getDoubleExtra(LiveTrackStreamingService.EXTRA_POINT_LON, 0.0)
            TrackUpdateHelper.updateTrack(trackPoints, trackTimestamps, LatLng(lat, lon), tsMs.takeIf { it > 0L } ?: System.currentTimeMillis())
            scheduleTrackLineUpdate()
            updateZoomToLatestButtonState()
            if (followLockEnabled && trackPoints.isNotEmpty()) {
                centerCameraOnTrackLocked(trackPoints.last())
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

        mapLoadingOverlay = view.findViewById(R.id.mapLoadingOverlay)
        trackerLabelCard = view.findViewById(R.id.trackerLabelCard)
        trackerNameLabel = view.findViewById(R.id.trackerNameLabel)
        resetToTrackerButton = view.findViewById(R.id.resetToTrackerButton)
        mapToggle = view.findViewById(R.id.mapToggle)
        zoomToLatestButton = view.findViewById(R.id.zoomToLatestButton)
        zoomToLatestButtonIcon = view.findViewById(R.id.zoomToLatestButtonIcon)
        zoomInButton = view.findViewById(R.id.zoomInButton)
        zoomOutButton = view.findViewById(R.id.zoomOutButton)
        geometryLoadingSpinner = view.findViewById(R.id.geometryLoadingSpinner)
        lastUpdatedLabel = view.findViewById(R.id.lastUpdatedLabel)
        showAllTrackersButton = view.findViewById(R.id.showAllTrackersButton)

        if (childFragmentManager.findFragmentById(R.id.mapContainer) == null) {
            childFragmentManager.beginTransaction()
                .replace(
                    R.id.mapContainer,
                    GeoVaultMapFragment().apply {
                        arguments = bundleOf(GeoVaultMapFragment.ARG_SHOW_TOGGLE to false)
                    }
                )
                .commitNow()
        }
        mapFragment = childFragmentManager.findFragmentById(R.id.mapContainer) as? GeoVaultMapFragment
        mapFragment?.setCallback(object : GeoVaultMapFragment.Callback {
            override fun onMapReady(map: MapLibreMap, style: Style) {
                maplibreMap = map
                map.setMinZoomPreference(MIN_ZOOM)
                mapManager = mapFragment?.mapManager
                val mgr = mapManager ?: return
                mgr.defaultPadding = getMapPaddingArray()
                val current = map.cameraPosition
                val padded = CameraPosition.Builder(current)
                    .padding(mgr.defaultPadding!!)
                    .build()
                map.moveCamera(CameraUpdateFactory.newCameraPosition(padded))
                mgr.addMarkerIcon(style, "marker-default", R.drawable.ic_marker_default)
                MapMarkerUtils.getMarkerBitmapWithTintedForeground(
                    requireContext(),
                    R.drawable.ic_track_direction_arrow_circle,
                    R.drawable.ic_track_direction_arrow_chevron_fill,
                    R.drawable.ic_track_direction_arrow_chevron_stroke,
                    parseHexToColor(null, requireContext())
                )?.let { bitmap ->
                    style.addImage("track-direction-arrow", bitmap)
                }
                style.addSource(GeoJsonSource(TRACK_SOURCE_ID))
                style.addSource(
                    GeoJsonSource(
                        TRACK_POSITION_SOURCE_ID,
                        GeoJsonOptions().apply { this["synchronousUpdate"] = true }
                    )
                )
                style.addSource(
                    GeoJsonSource(
                        TRACK_POSITION_ACCURACY_SOURCE_ID,
                        GeoJsonOptions().apply { this["synchronousUpdate"] = true }
                    )
                )
                LocationComponentHelper.activate(
                    map = map,
                    style = style,
                    context = requireContext(),
                    config = LocationComponentHelper.Config(
                        accuracyColor = parseHexToColor(null, requireContext()),
                        accuracyAlpha = 0.25f,
                        backgroundDrawable = R.drawable.ic_track_direction_arrow_circle,
                        foregroundDrawable = R.drawable.ic_track_direction_arrow,
                        renderMode = RenderMode.COMPASS
                    )
                )

                val outlineLayer = LineLayer(TRACK_OUTLINE_LAYER_ID, TRACK_SOURCE_ID).apply {
                    setProperties(
                        PropertyFactory.lineWidth(5f),
                        PropertyFactory.lineColor(org.maplibre.android.style.expressions.Expression.get("outlineColor")),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND)
                    )
                }
                val fillLayer = LineLayer(TRACK_FILL_LAYER_ID, TRACK_SOURCE_ID).apply {
                    setProperties(
                        PropertyFactory.lineWidth(3f),
                        PropertyFactory.lineColor(org.maplibre.android.style.expressions.Expression.get("lineColor")),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND)
                    )
                }
                val trackerBaseColor = parseHexToColor(null, requireContext())
                val accuracyFillColor = Color.argb(
                    64,
                    Color.red(trackerBaseColor),
                    Color.green(trackerBaseColor),
                    Color.blue(trackerBaseColor)
                )
                val accuracyLayer = FillLayer(TRACK_POSITION_ACCURACY_LAYER_ID, TRACK_POSITION_ACCURACY_SOURCE_ID).apply {
                    setProperties(
                        PropertyFactory.fillColor(accuracyFillColor)
                    )
                }
                val symbolLayer = SymbolLayer(TRACK_POSITION_LAYER_ID, TRACK_POSITION_SOURCE_ID).apply {
                    setProperties(
                        PropertyFactory.iconImage(org.maplibre.android.style.expressions.Expression.get("icon")),
                        PropertyFactory.iconSize(0.75f),
                        PropertyFactory.iconRotate(org.maplibre.android.style.expressions.Expression.get("rotate")),
                        PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true)
                    )
                }
                style.addLayer(outlineLayer)
                style.addLayer(fillLayer)
                style.addLayer(accuracyLayer)
                style.addLayer(symbolLayer)
                style.addSource(GeoJsonSource(ALL_TRACKS_SOURCE_ID))
                style.addSource(
                    GeoJsonSource(
                        ALL_TRACKS_POINTS_SOURCE_ID,
                        GeoJsonOptions().apply { this["synchronousUpdate"] = true }
                    )
                )
                val allTracksOutlineLayer = LineLayer(ALL_TRACKS_OUTLINE_LAYER_ID, ALL_TRACKS_SOURCE_ID).apply {
                    setProperties(
                        PropertyFactory.lineWidth(5f),
                        PropertyFactory.lineColor(org.maplibre.android.style.expressions.Expression.get("outlineColor")),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.visibility(Property.NONE)
                    )
                }
                val allTracksFillLayer = LineLayer(ALL_TRACKS_FILL_LAYER_ID, ALL_TRACKS_SOURCE_ID).apply {
                    setProperties(
                        PropertyFactory.lineWidth(3f),
                        PropertyFactory.lineColor(org.maplibre.android.style.expressions.Expression.get("lineColor")),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.visibility(Property.NONE)
                    )
                }
                val allTracksPointsLayer = SymbolLayer(ALL_TRACKS_POINTS_LAYER_ID, ALL_TRACKS_POINTS_SOURCE_ID).apply {
                    setProperties(
                        PropertyFactory.iconImage(org.maplibre.android.style.expressions.Expression.get("icon")),
                        PropertyFactory.iconSize(0.75f),
                        PropertyFactory.iconRotate(org.maplibre.android.style.expressions.Expression.get("rotate")),
                        PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true),
                        PropertyFactory.visibility(Property.NONE)
                    )
                }
                style.addLayer(allTracksOutlineLayer)
                style.addLayer(allTracksFillLayer)
                style.addLayer(allTracksPointsLayer)
                map.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                    override fun onMoveBegin(detector: org.maplibre.android.gestures.MoveGestureDetector) {
                        if (followLockEnabled) {
                            followLockEnabled = false
                            LocationComponentHelper.setCameraTracking(map, enabled = false)
                            updateFollowLockButton()
                        }
                    }
                    override fun onMove(detector: org.maplibre.android.gestures.MoveGestureDetector) { }
                    override fun onMoveEnd(detector: org.maplibre.android.gestures.MoveGestureDetector) { }
                })
                mapReady = true
                mapLoadingOverlay.visibility = View.GONE
                refreshMapPadding(force = true)
                updateTrackLine()
                if (zoomToTrackAfterLoad && trackPoints.isNotEmpty()) {
                    val bbox = (activity as? MainActivity)?.initialTrackForMap?.bbox
                    if (bbox != null && bbox.size == 4) {
                        val bounds = LatLngBounds.Builder()
                            .include(LatLng(bbox[1], bbox[0]))
                            .include(LatLng(bbox[3], bbox[2]))
                            .build()
                        moveCameraToFitBoundsWithMinZoomClamp(map, bounds)
                        zoomToTrackAfterLoad = false
                    } else if (trackPoints.size >= 2) {
                        val bounds = LatLngBounds.Builder().apply { trackPoints.forEach { include(it) } }.build()
                        moveCameraToFitBoundsWithMinZoomClamp(map, bounds)
                        zoomToTrackAfterLoad = false
                    }
                }
                fetchHistory()
            }
        })

        updateTrackerLabel()
        trackerLabelCard.setOnClickListener {
            (activity as? MainActivity)?.openTrackersAndScrollTo(displayedTrackerId)
        }
        resetToTrackerButton.setOnClickListener {
            TrackerRepository.cancelGeometryRequest()
            restoreTrackForSelectedTracker()
        }

        followLockEnabled = savedInstanceState?.getBoolean(KEY_FOLLOW_LOCK, false) ?: false
        updateFollowLockButton()
        updateZoomToLatestButtonState()

        requireActivity().supportFragmentManager.setFragmentResultListener(TrackersListFragment.REQUEST_REFRESH_LIST, viewLifecycleOwner) { _, bundle ->
            val hiddenId = bundle?.getString(TrackersListFragment.KEY_HIDDEN_TRACKER_ID) ?: return@setFragmentResultListener
            if (hiddenId == displayedTrackerId) {
                refreshTrackForSelectedTracker()
            }
        }

        mapToggle.setOnClickListener {
            val map = maplibreMap ?: return@setOnClickListener
            val mgr = mapManager ?: return@setOnClickListener
            mgr.sourceManager.setSelectedSourceId(mgr.sourceManager.getNextSourceId())
            mgr.applySelectedSource(map)
        }

        zoomToLatestButton.setOnClickListener {
            followLockEnabled = !followLockEnabled
            followLockNeedsInitialZoom = followLockEnabled
            if (followLockEnabled && trackPoints.isNotEmpty()) {
                centerCameraOnTrackLocked(trackPoints.last(), forceZoomIn = true)
            }
            updateFollowLockButton()
        }

        zoomInButton.setOnClickListener {
            maplibreMap?.let { map ->
                mapManager?.animateCameraWithPadding(map, CameraUpdateFactory.zoomBy(1.0), durationMs = 200)
            }
        }
        zoomOutButton.setOnClickListener {
            maplibreMap?.let { map ->
                mapManager?.animateCameraWithPadding(map, CameraUpdateFactory.zoomBy(-1.0), durationMs = 200)
            }
        }

        showAllTrackersButton.setOnClickListener {
            if (showAllTrackers) {
                showAllTrackers = false
                clearAllTrackSources()
                setAllTrackLayersVisibility(false)
                setAnnotationLayersVisibility(true)
                fetchHistory()
                updateTrackerLabel()
            } else {
                loadAllTrackersAndApply()
            }
        }
        view.post { if (isAdded) refreshMapPadding(force = true) }
    }

    override fun onResume() {
        super.onResume()
        view?.keepScreenOn = true
        updateTrackerLabel()
        refreshMapPadding(force = true)

        ContextCompat.registerReceiver(
            requireContext(),
            locationReceiver,
            IntentFilter("com.geovault.tracker.LOCATION_UPDATE"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            requireContext(),
            liveTrackPointReceiver,
            IntentFilter(LiveTrackStreamingService.BROADCAST_TRACK_POINT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Drain any points buffered while the map was not visible
        val buffered = LiveTrackStreamingService.drainBufferedPoints()
        if (buffered.isNotEmpty()) {
            for (p in buffered) {
                TrackUpdateHelper.updateTrack(trackPoints, trackTimestamps, LatLng(p.lat, p.lon), p.timestampMs)
                p.accuracyMeters?.let { lastStreamedAccuracyMeters = it }
            }
            val lastTs = buffered.last().timestampMs
            if (lastTs > 0L) lastStreamedPointTimeMs = lastTs
            scheduleTrackLineUpdate()
            updateZoomToLatestButtonState()
            if (followLockEnabled && trackPoints.isNotEmpty()) {
                centerCameraOnTrackLocked(trackPoints.last())
            }
        }

        if (mapReady) {
            if (mapViewContext == MapViewContext.GROUP) {
                updateTrackerLabel()
                return
            }
            val prefs = requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
            val defaultTrackerId = prefs.getString("selected_tracker_id", "") ?: ""
            if (defaultTrackerId.isEmpty()) {
                val hasSpecificTracker = !displayedTrackerId.isNullOrEmpty()
                val pendingInitialTracker = (activity as? MainActivity)?.initialTrackForMap != null
                if (!hasSpecificTracker && !pendingInitialTracker) {
                    // No default and no explicit tracker target: clear map so we don't show stale data.
                    trackPoints.clear()
                    displayedTrackerId = null
                    displayedTrackerName = null
                    stopLiveTrackStreaming()
                    updateTrackLine()
                    updateZoomToLatestButtonState()
                    updateTrackerLabel()
                } else if (hasSpecificTracker && trackPoints.isEmpty()) {
                    // No default exists, but user is viewing a specific tracker from list/share.
                    TrackerRepository.clearGeometryCache()
                    restoreOnlyNoZoom = true
                    fetchFullGeometryAndApply(displayedTrackerId!!, forceReplace = false)
                } else {
                    updateTrackerLabel()
                }
            } else {
                val showingDefault = displayedTrackerId == null || displayedTrackerId == defaultTrackerId
                if (showingDefault && trackPoints.isEmpty()) {
                    TrackerRepository.clearGeometryCache()
                    restoreOnlyNoZoom = true
                    fetchFullGeometryAndApply(defaultTrackerId, forceReplace = false)
                } else if (!showingDefault && displayedTrackerId != null) {
                    // Re-start streaming when returning to Map (e.g. after closing Params overlay).
                    startLiveTrackStreamingForDisplayedTracker()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        view?.keepScreenOn = false
        try {
            requireContext().unregisterReceiver(locationReceiver)
        } catch (e: IllegalArgumentException) { }
        try {
            requireContext().unregisterReceiver(liveTrackPointReceiver)
        } catch (e: IllegalArgumentException) { }
    }

    override fun onDestroyView() {
        mapFragment?.setCallback(null)
        mapFragment = null
        mapManager = null
        maplibreMap = null
        super.onDestroyView()
        mainScope.cancel()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_FOLLOW_LOCK, followLockEnabled)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapFragment?.mapView?.onLowMemory()
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
        val hasTrack = !showAllTrackers && trackPoints.isNotEmpty()
        zoomToLatestButton.isEnabled = hasTrack
        zoomToLatestButton.alpha = if (hasTrack) 1f else 0.4f
    }

    private fun updateTrackerLabel() {
        val prefs = requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
        val defaultTrackerId = prefs.getString("selected_tracker_id", "") ?: ""
        val defaultTrackerName = prefs.getString("selected_tracker_name", "") ?: ""
        if (mapViewContext == MapViewContext.GROUP) {
            trackerLabelCard.visibility = View.VISIBLE
            val density = resources.displayMetrics.density
            val maxAllowedWidth = (resources.displayMetrics.widthPixels * 2) / 3
            trackerLabelCard.layoutParams = trackerLabelCard.layoutParams.apply {
                width = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            trackerNameLabel.maxWidth = maxAllowedWidth - (58 * density).toInt()
            val updatedFixedDesiredWidth = (160 * density).toInt()
            val cappedUpdatedWidth = updatedFixedDesiredWidth.coerceAtMost(maxAllowedWidth - (34 * density).toInt())
            lastUpdatedLabel.layoutParams = lastUpdatedLabel.layoutParams.apply {
                width = cappedUpdatedWidth
            }
            lastUpdatedLabel.maxWidth = cappedUpdatedWidth
            trackerNameLabel.text = displayedGroupName?.takeIf { it.isNotBlank() } ?: getString(R.string.groups_title)
            resetToTrackerButton.visibility = View.VISIBLE
            resetToTrackerButton.contentDescription = getString(R.string.show_default_tracker)
            updateStreamingUi(defaultTrackerId)
            showAllTrackersButton.visibility = View.GONE
            showAllTrackersButton.contentDescription = getString(R.string.show_all_trackers)
            return
        }
        val showingSpecificTracker = !showAllTrackers &&
            mapViewContext == MapViewContext.SPECIFIC_TRACKER &&
            !displayedTrackerId.isNullOrEmpty()
        if (defaultTrackerId.isEmpty() && !showingSpecificTracker) {
            trackerLabelCard.visibility = View.GONE
            displayedTrackerId = null
            displayedTrackerName = null
            displayedGroupName = null
            mapViewContext = MapViewContext.DEFAULT_TRACKER
            lastCachedUpdateTimeMs = null
            updateStreamingUi("")
            showAllTrackersButton.visibility = View.VISIBLE
            showAllTrackersButton.contentDescription = if (showAllTrackers) getString(R.string.show_default_tracker) else getString(R.string.show_all_trackers)
        } else {
            trackerLabelCard.visibility = View.VISIBLE
            val density = resources.displayMetrics.density
            val maxAllowedWidth = (resources.displayMetrics.widthPixels * 2) / 3
            
            // Card wraps to its ConstraintLayout content
            trackerLabelCard.layoutParams = trackerLabelCard.layoutParams.apply {
                width = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            
            // Constrain text widths so the card doesn't exceed 2/3 screen width
            // Top row overhead: paddingStart(14) + paddingEnd(8) + ResetButton(28) + marginEnd(8) = 58dp
            trackerNameLabel.maxWidth = maxAllowedWidth - (58 * density).toInt()

            // Bottom row overhead: paddingStart(14) + lastUpdatedPaddingEnd(12) + paddingEnd(8) = 34dp
            // Use a fixed width for the lastUpdatedLabel so it doesn't jitter the card width!
            val updatedFixedDesiredWidth = (160 * density).toInt()
            val cappedUpdatedWidth = updatedFixedDesiredWidth.coerceAtMost(maxAllowedWidth - (34 * density).toInt())
            lastUpdatedLabel.layoutParams = lastUpdatedLabel.layoutParams.apply {
                width = cappedUpdatedWidth
            }
            lastUpdatedLabel.maxWidth = cappedUpdatedWidth

            val labelName = if (showAllTrackers) {
                getString(R.string.show_all_trackers)
            } else {
                displayedTrackerName?.takeIf { it.isNotBlank() }
                    ?: defaultTrackerName.takeIf { it.isNotBlank() }
                    ?: getString(R.string.select_tracker)
            }
            trackerNameLabel.text = labelName
            // Show reset when we're viewing a track that is not the default (e.g. after "View on map" on another track); hide when showing all trackers
            resetToTrackerButton.visibility = if (showAllTrackers) {
                View.GONE
            } else if (mapViewContext != MapViewContext.DEFAULT_TRACKER) {
                View.VISIBLE
            } else {
                View.GONE
            }
            resetToTrackerButton.contentDescription = getString(R.string.show_default_tracker)
            updateStreamingUi(defaultTrackerId)
            showAllTrackersButton.visibility = if (mapViewContext == MapViewContext.DEFAULT_TRACKER) View.VISIBLE else View.GONE
            showAllTrackersButton.contentDescription = if (showAllTrackers) getString(R.string.show_default_tracker) else getString(R.string.show_all_trackers)
        }
        mapManager?.defaultPadding = getMapPaddingArray()
    }

    /** Return true if we are currently viewing a track that is NOT the default one. */
    fun isShowingStreamedTrack(): Boolean {
        if (!isAdded) return false
        val prefs = requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
        val defaultTrackerId = prefs.getString("selected_tracker_id", "") ?: ""
        return isStreaming(defaultTrackerId)
    }

    /** True when the displayed track is a non-default (streamed) track. */
    private fun isStreaming(defaultTrackerId: String): Boolean {
        return displayedTrackerId != null && defaultTrackerId.isNotEmpty() && displayedTrackerId != defaultTrackerId
    }

    private fun updateStreamingUi(defaultTrackerId: String) {
        if (isStreaming(defaultTrackerId)) {
            val effectiveTs = lastStreamedPointTimeMs ?: lastCachedUpdateTimeMs
            effectiveTs?.let { ts ->
                lastUpdatedLabel.visibility = View.VISIBLE
                val diffMs = System.currentTimeMillis() - ts
                val diffSec = (diffMs / 1000).coerceAtLeast(0)
                val (n, unitResId) = when {
                    diffSec < 60 -> {
                        val n = diffSec.toInt()
                        n to if (n == 1) R.string.last_updated_second else R.string.last_updated_seconds
                    }
                    diffSec < 3600 -> {
                        val n = (diffSec / 60).toInt()
                        n to if (n == 1) R.string.last_updated_minute else R.string.last_updated_minutes
                    }
                    diffSec < 86400 -> {
                        val n = (diffSec / 3600).toInt()
                        n to if (n == 1) R.string.last_updated_hour else R.string.last_updated_hours
                    }
                    else -> {
                        val n = (diffSec / 86400).toInt()
                        n to if (n == 1) R.string.last_updated_day else R.string.last_updated_days
                    }
                }
                lastUpdatedLabel.text = getString(R.string.last_updated_streaming, n, getString(unitResId))
            } ?: run {
                lastUpdatedLabel.visibility = View.GONE
            }
        } else {
            lastStreamedPointTimeMs = null
            lastCachedUpdateTimeMs = null
            lastUpdatedLabel.visibility = View.GONE
        }
        updateBottomRightSpinner(defaultTrackerId)
    }

    /** Extract last update timestamp (ms) from tracker geometry or last_point; same convention as TrackersListFragment. */
    private fun trackerLastUpdateMs(tracker: Tracker?): Long? {
        if (tracker == null) return null
        val coord = tracker.geometry?.coordinates?.lastOrNull() ?: tracker.last_point ?: return null
        if (coord.size < 3) return null
        val t = (coord[2] as? Number)?.toLong() ?: return null
        return if (t < 1e12) t * 1000 else t
    }

    /** Show bottom-right spinner when loading geometry or when streaming a non-default track. */
    private fun updateBottomRightSpinner(defaultTrackerId: String) {
        val show = geometryLoadingInProgress || isStreaming(defaultTrackerId)
        if (show) geometryLoadingSpinner.show() else geometryLoadingSpinner.hide()
    }

    private fun updateLocationOnMap(location: Location) {
        val latLng = LatLng(location.latitude, location.longitude)
        TrackUpdateHelper.updateTrack(trackPoints, trackTimestamps, latLng, location.time)
        val map = maplibreMap
        if (map != null) {
            scheduleTrackLineUpdate()
            updateZoomToLatestButtonState()
            if (followLockEnabled) {
                centerCameraOnTrackLocked(latLng)
            }
        }
    }

    /**
     * Keeps the map centered on the given point when follow lock is on.
     * When [forceZoomIn] is true, make sure we jump to follow zoom first.
     */
    private fun centerCameraOnTrackLocked(target: LatLng, forceZoomIn: Boolean = false) {
        val map = maplibreMap ?: return
        val shouldForceZoom = forceZoomIn || followLockNeedsInitialZoom
        if (shouldForceZoom) followLockNeedsInitialZoom = false
        val zoom = if (shouldForceZoom) {
            maxOf(map.cameraPosition.zoom, FOLLOW_LOCK_TARGET_ZOOM)
        } else {
            map.cameraPosition.zoom
        }
        val update = CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder().target(target).zoom(zoom).build()
        )
        mapManager?.animateCameraWithPadding(map, update, getMapPaddingArray(), FOLLOW_LOCK_ANIMATION_MS)
    }

    private fun getMapPaddingArray(): DoubleArray {
        val density = resources.displayMetrics.density
        val mapRoot = view
        val mapWidthPx = mapRoot?.width ?: 0
        val mapHeightPx = mapRoot?.height ?: 0
        val baseLeftPx = (MAP_PADDING_LEFT_DP * density).toInt()
        val baseTopPx = (MAP_PADDING_TOP_DP * density).toInt()
        val baseRightPx = (MAP_PADDING_RIGHT_DP * density).toInt()
        val baseBottomPx = (MAP_PADDING_BOTTOM_DP * density).toInt()
        val extraPadPx = (MAP_PADDING_EDGE_EXTRA_DP * density).toInt()
        val leftOverlayInsetPx = if (mapWidthPx > 0 && trackerLabelCard.visibility == View.VISIBLE) {
            trackerLabelCard.right.coerceAtLeast(0)
        } else 0
        val leftPaddingPx = maxOf(
            baseLeftPx,
            if (leftOverlayInsetPx > 0) leftOverlayInsetPx + extraPadPx else baseLeftPx
        )
        val topOverlayBottomPx = listOf(
            trackerLabelCard,
            zoomToLatestButton,
            mapToggle,
            zoomInButton,
            zoomOutButton,
            showAllTrackersButton
        )
            .filter { it.visibility == View.VISIBLE }
            .maxOfOrNull { it.top + it.height }
            ?: 0
        val topPaddingPx = maxOf(
            baseTopPx,
            if (topOverlayBottomPx > 0) topOverlayBottomPx + extraPadPx else baseTopPx
        )
        val rightOverlayInsetPx = if (mapWidthPx > 0) {
            listOf(zoomToLatestButton, mapToggle, zoomInButton, zoomOutButton, showAllTrackersButton)
                .filter { it.visibility == View.VISIBLE }
                .maxOfOrNull { (mapWidthPx - it.left).coerceAtLeast(0) }
                ?: 0
        } else 0
        val rightPaddingPx = maxOf(
            baseRightPx,
            if (rightOverlayInsetPx > 0) rightOverlayInsetPx + extraPadPx else baseRightPx
        )
        val bottomOverlayInsetPx = if (mapHeightPx > 0 && geometryLoadingSpinner.visibility == View.VISIBLE) {
            (mapHeightPx - geometryLoadingSpinner.top).coerceAtLeast(0)
        } else 0
        val bottomNavOverlapPx = run {
            val nav = activity?.findViewById<View>(R.id.bottomNavContainer)
            if (mapRoot == null || nav == null || !nav.isShown) 0
            else {
                val mapLoc = IntArray(2)
                val navLoc = IntArray(2)
                mapRoot.getLocationOnScreen(mapLoc)
                nav.getLocationOnScreen(navLoc)
                val mapBottom = mapLoc[1] + mapRoot.height
                (mapBottom - navLoc[1]).coerceAtLeast(0)
            }
        }
        val bottomPaddingPx = maxOf(
            baseBottomPx,
            if (bottomOverlayInsetPx > 0) bottomOverlayInsetPx + extraPadPx else baseBottomPx,
            if (bottomNavOverlapPx > 0) bottomNavOverlapPx + extraPadPx else baseBottomPx,
            ((activity?.findViewById<View>(R.id.bottomNavContainer)?.height ?: 0) + extraPadPx)
        )
        return doubleArrayOf(
            leftPaddingPx.toDouble(),
            topPaddingPx.toDouble(),
            rightPaddingPx.toDouble(),
            bottomPaddingPx.toDouble()
        )
    }

    /** Per-edge bounds-fit padding: current map insets plus extra buffer. */
    private fun getBoundsPaddingEdgesPx(extraBoundsPaddingPx: Int): IntArray {
        val insets = getMapPaddingArray()
        return intArrayOf(
            insets[0].toInt() + extraBoundsPaddingPx,
            insets[1].toInt() + extraBoundsPaddingPx,
            insets[2].toInt() + extraBoundsPaddingPx,
            insets[3].toInt() + extraBoundsPaddingPx
        )
    }

    /** Web Mercator X world-pixel at [zoom], wrapping longitudes. */
    private fun worldXAtZoom(lonDeg: Double, zoom: Double): Double {
        val worldSize = 256.0 * 2.0.pow(zoom)
        var norm = ((lonDeg + 180.0) / 360.0) % 1.0
        if (norm < 0.0) norm += 1.0
        return norm * worldSize
    }

    /** Web Mercator Y world-pixel at [zoom], clamped to supported latitude range. */
    private fun worldYAtZoom(latDeg: Double, zoom: Double): Double {
        val worldSize = 256.0 * 2.0.pow(zoom)
        val lat = latDeg.coerceIn(-85.05112878, 85.05112878)
        val latRad = lat * kotlin.math.PI / 180.0
        val mercN = ln(tan(kotlin.math.PI / 4.0 + latRad / 2.0))
        return (0.5 - mercN / (2.0 * kotlin.math.PI)) * worldSize
    }

    private fun wrappedPixelDelta(a: Double, b: Double, worldSize: Double): Double {
        val d = abs(a - b)
        return min(d, worldSize - d)
    }

    private fun worldXToLonDeg(x: Double, worldSize: Double): Double {
        var norm = (x / worldSize) % 1.0
        if (norm < 0.0) norm += 1.0
        return norm * 360.0 - 180.0
    }

    private fun worldYToLatDeg(y: Double, worldSize: Double): Double {
        val yy = y.coerceIn(0.0, worldSize)
        val n = kotlin.math.PI * (1.0 - 2.0 * yy / worldSize)
        return atan(sinh(n)) * 180.0 / kotlin.math.PI
    }

    /**
     * Move camera to fit [bounds] if resulting zoom >= [MIN_ZOOM]; otherwise center on [bounds].center at MIN_ZOOM (single-track / tight bbox).
     */
    private fun moveCameraToFitBoundsWithMinZoomClamp(map: MapLibreMap, bounds: LatLngBounds) {
        val mgr = mapManager ?: return
        val p = getBoundsPaddingEdgesPx(0)
        val boundsUpdate = CameraUpdateFactory.newLatLngBounds(bounds, p[0], p[1], p[2], p[3])
        val pos = boundsUpdate.getCameraPosition(map)
        if (pos != null && pos.zoom.toDouble() >= MIN_ZOOM) {
            mgr.moveCameraWithPadding(map, boundsUpdate, getMapPaddingArray())
        } else {
            val center = bounds.center
            mgr.moveCameraWithPadding(
                map,
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(center)
                        .zoom(MIN_ZOOM)
                        .tilt(0.0)
                        .bearing(0.0)
                        .build()
                ),
                getMapPaddingArray()
            )
        }
    }

    /**
     * All-trackers fit: fit full bounds if zoom allows; else viewport at MIN_ZOOM that contains the most tracker last-points; else one tracker at MIN_ZOOM.
     */
    private fun moveCameraForAllTrackersWithMinZoom(
        map: MapLibreMap,
        bounds: LatLngBounds,
        coordsByTrackerId: Map<String, List<LatLng>>,
        trackers: List<Tracker>,
        fitToTrackerId: String?
    ) {
        val mgr = mapManager ?: return
        val p = getBoundsPaddingEdgesPx(0)
        val boundsUpdate = CameraUpdateFactory.newLatLngBounds(bounds, p[0], p[1], p[2], p[3])
        val pos = boundsUpdate.getCameraPosition(map)
        // If fit computes exactly MIN_ZOOM, that can still mean "clamped from lower zoom";
        // in that case we should run fit-most instead of assuming all points fit.
        if (pos != null && pos.zoom.toDouble() > (MIN_ZOOM + MIN_ZOOM_EPSILON)) {
            Log.d(
                TAG,
                "all-trackers fit all: zoom=${pos.zoom}, minZoom=$MIN_ZOOM, trackerCount=${trackers.size}"
            )
            mgr.moveCameraWithPadding(map, boundsUpdate, getMapPaddingArray())
            return
        }
        if (fitToTrackerId != null) {
            Log.d(
                TAG,
                "all-trackers fit specific tracker path: fitToTrackerId=$fitToTrackerId (using min-zoom clamp helper)"
            )
            moveCameraToFitBoundsWithMinZoomClamp(map, bounds)
            return
        }
        val repTrackerPoints = trackers.mapNotNull { t ->
            coordsByTrackerId[t.id]?.lastOrNull()?.let { t.id to it }
        }
        if (repTrackerPoints.size <= 1) {
            val target = repTrackerPoints.firstOrNull()?.second
                ?: trackers.firstNotNullOfOrNull { t -> coordsByTrackerId[t.id]?.lastOrNull() }
                ?: bounds.center
            Log.d(
                TAG,
                "all-trackers fallback(single): representativeTrackerCount=${repTrackerPoints.size}, minZoom=$MIN_ZOOM, target=(${target.latitude},${target.longitude})"
            )
            mgr.moveCameraWithPadding(
                map,
                CameraUpdateFactory.newLatLngZoom(target, MIN_ZOOM),
                getMapPaddingArray()
            )
            return
        }
        val visibleW = (map.width - p[0] - p[2]).coerceAtLeast(1f).toDouble()
        val visibleH = (map.height - p[1] - p[3]).coerceAtLeast(1f).toDouble()
        val halfW = visibleW * 0.5
        val halfH = visibleH * 0.5
        val worldSize = 256.0 * 2.0.pow(MIN_ZOOM)

        val pointsProjected = repTrackerPoints.map { (trackerId, pt) ->
            Quad(
                trackerId,
                pt,
                worldXAtZoom(pt.longitude, MIN_ZOOM),
                worldYAtZoom(pt.latitude, MIN_ZOOM)
            )
        }

        // Max-coverage search for fixed-size viewport at MIN_ZOOM.
        // Candidate centers are derived from projected point edges, not only point positions.
        val candidateXs = mutableSetOf<Double>()
        val candidateYs = mutableSetOf<Double>()
        for ((_, _, px, py) in pointsProjected) {
            candidateXs.add(px)
            candidateXs.add(px - halfW)
            candidateXs.add(px + halfW)
            candidateYs.add(py)
            candidateYs.add(py - halfH)
            candidateYs.add(py + halfH)
        }
        val center = bounds.center
        candidateXs.add(worldXAtZoom(center.longitude, MIN_ZOOM))
        candidateYs.add(worldYAtZoom(center.latitude, MIN_ZOOM))

        var bestCount = 0
        var bestCx = candidateXs.first()
        var bestCy = candidateYs.first()
        for (cx in candidateXs) {
            for (cy in candidateYs) {
                val cnt = pointsProjected.count { (_, _, px, py) ->
                    wrappedPixelDelta(px, cx, worldSize) <= halfW && abs(py - cy) <= halfH
                }
                if (cnt > bestCount) {
                    bestCount = cnt
                    bestCx = cx
                    bestCy = cy
                }
            }
        }
        val bestCenter = LatLng(
            worldYToLatDeg(bestCy, worldSize),
            worldXToLonDeg(bestCx, worldSize)
        )
        val includedTrackerIds = pointsProjected
            .filter { (_, _, px, py) ->
                wrappedPixelDelta(px, bestCx, worldSize) <= halfW && abs(py - bestCy) <= halfH
            }
            .map { it.first }
        val excludedTrackerIds = repTrackerPoints.map { it.first }.filterNot { includedTrackerIds.contains(it) }
        Log.d(
            TAG,
            "all-trackers fit-most: total=${repTrackerPoints.size}, included=${includedTrackerIds.size}, excluded=${excludedTrackerIds.size}, minZoom=$MIN_ZOOM, visiblePx=(${visibleW.toInt()}x${visibleH.toInt()}), center=(${bestCenter.latitude},${bestCenter.longitude}), includedIds=$includedTrackerIds, excludedIds=$excludedTrackerIds"
        )
        if (bestCount <= 1) {
            val ordered = trackers.mapNotNull { t -> coordsByTrackerId[t.id]?.lastOrNull() }
            val target = ordered.firstOrNull() ?: repTrackerPoints.first().second
            Log.d(
                TAG,
                "all-trackers fallback(one-at-min-zoom): bestCount=$bestCount, minZoom=$MIN_ZOOM, target=(${target.latitude},${target.longitude})"
            )
            mgr.moveCameraWithPadding(
                map,
                CameraUpdateFactory.newLatLngZoom(target, MIN_ZOOM),
                getMapPaddingArray()
            )
        } else {
            mgr.moveCameraWithPadding(
                map,
                CameraUpdateFactory.newLatLngZoom(bestCenter, MIN_ZOOM),
                getMapPaddingArray()
            )
        }
    }

    private data class Quad(
        val first: String,
        val second: LatLng,
        val third: Double,
        val fourth: Double
    )

    /**
     * Survey-style padding refresh: store default padding and actively apply it
     * to the current camera position so UI inset changes immediately take effect.
     */
    private fun refreshMapPadding(force: Boolean = false) {
        val map = maplibreMap ?: return
        val mgr = mapManager ?: return
        val targetPadding = getMapPaddingArray()
        val currentPadding = map.cameraPosition.padding
        val isSamePadding = currentPadding != null &&
            kotlin.math.abs(currentPadding[0] - targetPadding[0]) < 1.0 &&
            kotlin.math.abs(currentPadding[1] - targetPadding[1]) < 1.0 &&
            kotlin.math.abs(currentPadding[2] - targetPadding[2]) < 1.0 &&
            kotlin.math.abs(currentPadding[3] - targetPadding[3]) < 1.0
        mgr.defaultPadding = targetPadding
        if (!force && isSamePadding) return
        val padded = CameraPosition.Builder(map.cameraPosition)
            .padding(targetPadding)
            .build()
        map.moveCamera(CameraUpdateFactory.newCameraPosition(padded))
    }

    /**
     * Coalesce rapid track line updates into one GeoJSON rebuild per vsync frame.
     * Prevents redundant work when multiple points arrive within the same frame.
     */
    private fun scheduleTrackLineUpdate() {
        if (trackLineDirty) return
        trackLineDirty = true
        Choreographer.getInstance().postFrameCallback {
            if (trackLineDirty) {
                trackLineDirty = false
                updateTrackLine()
            }
        }
    }

    /**
     * Clear the map track and refetch only the currently selected tracker.
     * Call this when switching to the map from "View on map" so only that tracker is shown.
     * If the list provided an initial track (latest 100 points), shows it immediately then loads full geometry in background.
     */
    fun refreshTrackForSelectedTracker() {
        showAllTrackers = false
        displayedGroupName = null
        clearAllTrackSources()
        setAllTrackLayersVisibility(false)
        setAnnotationLayersVisibility(true)

        val initial = (activity as? MainActivity)?.getAndClearInitialTrackForMap()
        val prefs = requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
        val defaultTrackerId = prefs.getString("selected_tracker_id", "") ?: ""
        val loadTrackerId = if (initial != null) initial.id else defaultTrackerId
        mapViewContext = if (loadTrackerId.isNotEmpty() && (defaultTrackerId.isEmpty() || loadTrackerId != defaultTrackerId)) {
            MapViewContext.SPECIFIC_TRACKER
        } else {
            MapViewContext.DEFAULT_TRACKER
        }

        val isSwitching = displayedTrackerId != null && displayedTrackerId != loadTrackerId

        // Immediate visual clear: hide annotation layers so old data doesn't flash.
        if (isSwitching) {
            setAnnotationLayersVisibility(false)
            mapFragment?.mapView?.alpha = 0f
            mapFragment?.mapView?.animate()?.alpha(1f)?.setDuration(200)?.setStartDelay(50)?.start()
        }

        trackPoints.clear()
        trackTimestamps.clear()
        updateTrackLine()
        zoomToTrackAfterLoad = true
        followLockEnabled = false
        updateFollowLockButton()

        if (loadTrackerId.isEmpty()) {
            displayedTrackerId = null
            displayedTrackerName = null
            stopLiveTrackStreaming()
            updateZoomToLatestButtonState()
            updateTrackerLabel()
            return
        }
        if (isSwitching) {
            // Give MapLibre's async GL renderer a tiny moment to erase the old tracker's
            // GeoJSON source from the screen before we jump the camera to the new BBox.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (isAdded) {
                    applyInitialTargetTracker(initial, loadTrackerId, defaultTrackerId, prefs)
                }
            }, 50)
        } else {
            applyInitialTargetTracker(initial, loadTrackerId, defaultTrackerId, prefs)
        }
    }

    private fun setAnnotationLayersVisibility(visible: Boolean) {
        val style = maplibreMap?.style ?: return
        val visibility = if (visible) Property.VISIBLE else Property.NONE
        
        style.getLayer(TRACK_OUTLINE_LAYER_ID)?.setProperties(PropertyFactory.visibility(visibility))
        style.getLayer(TRACK_FILL_LAYER_ID)?.setProperties(PropertyFactory.visibility(visibility))
        style.getLayer(TRACK_POSITION_ACCURACY_LAYER_ID)?.setProperties(PropertyFactory.visibility(visibility))
        style.getLayer(TRACK_POSITION_LAYER_ID)?.setProperties(PropertyFactory.visibility(visibility))
        
        style.layers.forEach { layer ->
            // Annotation plugin layers start with this prefix.
            val id = layer.id
            if (id.startsWith("mapbox-android-") || id.startsWith("org.maplibre.annotations")) {
                layer.setProperties(PropertyFactory.visibility(visibility))
            }
        }
    }

    private fun setAllTrackLayersVisibility(visible: Boolean) {
        val style = maplibreMap?.style ?: return
        val visibility = if (visible) Property.VISIBLE else Property.NONE
        style.getLayer(ALL_TRACKS_OUTLINE_LAYER_ID)?.setProperties(PropertyFactory.visibility(visibility))
        style.getLayer(ALL_TRACKS_FILL_LAYER_ID)?.setProperties(PropertyFactory.visibility(visibility))
        style.getLayer(ALL_TRACKS_POINTS_LAYER_ID)?.setProperties(PropertyFactory.visibility(visibility))
    }

    private fun clearAllTrackSources() {
        val style = maplibreMap?.style ?: return
        style.getSourceAs<GeoJsonSource>(ALL_TRACKS_SOURCE_ID)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        style.getSourceAs<GeoJsonSource>(ALL_TRACKS_POINTS_SOURCE_ID)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
    }

    /**
     * Fit map to the given group's trackers (show only those tracks and fit bounds).
     * Call when user taps "View group on map" from group detail.
     * @param zoomToTrackerId when set (e.g. user tapped a single tracker in the group), camera fits that tracker only; otherwise fits entire group.
     */
    fun refreshMapForGroup(group: Group?, zoomToTrackerId: String? = null) {
        if (group == null) return
        val map = maplibreMap ?: return
        val style = map.style ?: return
        mapViewContext = MapViewContext.GROUP
        displayedGroupName = group.name
        showAllTrackers = true
        clearAllTrackSources()
        setAllTrackLayersVisibility(false)
        setAnnotationLayersVisibility(false)
        trackPoints.clear()
        trackTimestamps.clear()
        updateTrackLine()
        displayedTrackerId = null
        displayedTrackerName = null
        stopLiveTrackStreaming()
        updateTrackerLabel()
        updateZoomToLatestButtonState()
        val trackIds = group.track_ids?.toSet() ?: emptySet()
        if (trackIds.isEmpty()) {
            setAllTrackLayersVisibility(true)
            return
        }
        TrackerRepository.getTrackers(requireContext(), forceRefresh = false) { list ->
            if (!isAdded) return@getTrackers
            val allTrackers = list ?: emptyList()
            val trackers = allTrackers.filter { it.id in trackIds }
            if (trackers.isEmpty()) {
                setAllTrackLayersVisibility(true)
                return@getTrackers
            }
            applyAllTrackersToMap(trackers, emptyMap(), map, style, fitBounds = true, fitToTrackerId = zoomToTrackerId)
            val coordsById = mutableMapOf<String, List<List<Double>>>()
            var remaining = trackers.size
            trackers.forEach { tracker ->
                TrackerRepository.getTrackerCoordinates(requireContext(), tracker.id) { response ->
                    mainScope.launch {
                        if (!isAdded || !showAllTrackers) return@launch
                        val coords = response?.coordinates ?: emptyList()
                        if (coords.isNotEmpty()) {
                            coordsById[tracker.id] = coords
                        }
                        remaining--
                        applyAllTrackersToMap(
                            trackers,
                            coordsById,
                            map,
                            style,
                            fitBounds = remaining == 0,
                            fitToTrackerId = zoomToTrackerId
                        )
                    }
                }
            }
        }
    }

    private fun loadAllTrackersAndApply() {
        val map = maplibreMap ?: return
        val style = map.style ?: return
        TrackerRepository.getMapVisibility(requireContext()) { visibility ->
            if (!isAdded) return@getMapVisibility
            val hiddenTrackIds = (visibility?.hidden_track_ids ?: emptyList()).toSet()
            TrackerRepository.getGroups(requireContext(), forceRefresh = false) { groupsList ->
                if (!isAdded) return@getGroups
                val hiddenGroupIds = (visibility?.hidden_group_ids ?: emptyList()).toSet()
                val groupsToHideFromMap = (groupsList ?: emptyList())
                    .filter { it.id in hiddenGroupIds || it.hidden_in_list == true }
                val trackIdsInHiddenGroups = groupsToHideFromMap
                    .flatMap { it.track_ids ?: emptyList() }
                    .toSet()
                val allHiddenTrackIds = hiddenTrackIds + trackIdsInHiddenGroups
                TrackerRepository.getTrackers(requireContext(), forceRefresh = false) { list ->
                    if (!isAdded) return@getTrackers
                    val allTrackers = list ?: emptyList()
                    val trackers = allTrackers.filter { it.id !in allHiddenTrackIds }
                    if (trackers.isEmpty()) {
                        showAllTrackers = true
                        clearAllTrackSources()
                        setAllTrackLayersVisibility(true)
                        setAnnotationLayersVisibility(false)
                        updateTrackerLabel()
                        updateZoomToLatestButtonState()
                        return@getTrackers
                    }
                    showAllTrackers = true
                    applyAllTrackersToMap(trackers, emptyMap(), map, style, fitBounds = true)
                    val coordsById = mutableMapOf<String, List<List<Double>>>()
                    var remaining = trackers.size
                    trackers.forEach { tracker ->
                        TrackerRepository.getTrackerCoordinates(requireContext(), tracker.id) { response ->
                            mainScope.launch {
                                if (!isAdded || !showAllTrackers) return@launch
                                val coords = response?.coordinates ?: emptyList()
                                if (coords.isNotEmpty()) {
                                    coordsById[tracker.id] = coords
                                }
                                remaining--
                                applyAllTrackersToMap(
                                    trackers,
                                    coordsById,
                                    map,
                                    style,
                                    fitBounds = remaining == 0
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun applyAllTrackersToMap(
        trackers: List<Tracker>,
        coordsById: Map<String, List<List<Double>>>,
        map: MapLibreMap,
        style: Style,
        fitBounds: Boolean,
        fitToTrackerId: String? = null
    ) {
        val outlineColor = String.format(
            "#%06X",
            0xFFFFFF and ContextCompat.getColor(requireContext(), R.color.track_line_outline)
        )
        val defaultColor = defaultTrackerColorHex(requireContext()).let { if (it.startsWith("#")) it else "#$it" }
        val lineFeatures = mutableListOf<Feature>()
        val pointFeatures = mutableListOf<Feature>()
        val allCoords = mutableListOf<LatLng>()
        val coordsByTrackerId = mutableMapOf<String, MutableList<LatLng>>()

        for (tracker in trackers) {
            val trackerCoords = mutableListOf<LatLng>()
            val coords = coordsById[tracker.id] ?: tracker.geometry?.coordinates ?: emptyList()
            if (coords.isEmpty()) {
                tracker.last_point?.takeIf { it.size >= 2 }?.let { lp ->
                    val pt = LatLng(lp[1], lp[0])
                    allCoords.add(pt)
                    trackerCoords.add(pt)
                    val hexColor = tracker.color?.let { if (it.startsWith("#")) it else "#$it" } ?: defaultColor
                    ensureArrowImageInStyle(style, hexColor, chevronOnly = true)
                    val imageId = "track-direction-arrow-simple-${hexColor.replace("#", "")}"
                    val pointFeature = Feature.fromGeometry(Point.fromLngLat(lp[0], lp[1]))
                    pointFeature.addStringProperty("icon", imageId)
                    pointFeatures.add(pointFeature)
                }
                coordsByTrackerId[tracker.id] = trackerCoords
                continue
            }
            val lastN = coords.takeLast(TrackUpdateHelper.MAX_POINTS)
            val points = lastN.map { c -> LatLng((c[1] as Number).toDouble(), (c[0] as Number).toDouble()) }
            points.forEach { allCoords.add(it); trackerCoords.add(it) }
            coordsByTrackerId[tracker.id] = trackerCoords
            val lineColor = (tracker.color?.let { if (it.startsWith("#")) it else "#$it" } ?: defaultColor)
            val segments = splitTrackIntoSegments(points)
            for (segment in segments) {
                if (segment.size < 2) continue
                val lineString = LineString.fromLngLats(segment.map { org.maplibre.geojson.Point.fromLngLat(it.longitude, it.latitude) })
                val feature = Feature.fromGeometry(lineString)
                feature.addStringProperty("outlineColor", outlineColor)
                feature.addStringProperty("lineColor", lineColor)
                lineFeatures.add(feature)
            }
            val lastPoint = points.last()
            val rotation = if (points.size >= 2) getTrackDirectionDegrees(points) else 0f
            val hexColor = tracker.color?.let { if (it.startsWith("#")) it else "#$it" } ?: defaultColor
            ensureArrowImageInStyle(style, hexColor, chevronOnly = true)
            val imageId = "track-direction-arrow-simple-${hexColor.replace("#", "")}"
            val pointFeature = Feature.fromGeometry(Point.fromLngLat(lastPoint.longitude, lastPoint.latitude))
            pointFeature.addStringProperty("icon", imageId)
            pointFeature.addNumberProperty("rotate", rotation.toDouble())
            pointFeatures.add(pointFeature)
        }

        style.getSourceAs<GeoJsonSource>(ALL_TRACKS_SOURCE_ID)?.setGeoJson(FeatureCollection.fromFeatures(lineFeatures))
        style.getSourceAs<GeoJsonSource>(ALL_TRACKS_POINTS_SOURCE_ID)?.setGeoJson(FeatureCollection.fromFeatures(pointFeatures))
        setAllTrackLayersVisibility(true)
        setAnnotationLayersVisibility(false)
        updateTrackerLabel()
        updateZoomToLatestButtonState()

        if (fitBounds) {
            val boundsCoords = if (fitToTrackerId != null) (coordsByTrackerId[fitToTrackerId] ?: emptyList()) else allCoords
            if (boundsCoords.isNotEmpty()) {
                if (boundsCoords.size >= 2) {
                    val boundsBuilder = LatLngBounds.Builder()
                    boundsCoords.forEach { boundsBuilder.include(it) }
                    val bounds = boundsBuilder.build()
                    moveCameraForAllTrackersWithMinZoom(
                        map, bounds, coordsByTrackerId, trackers, fitToTrackerId
                    )
                } else {
                    mapManager?.moveCameraWithPadding(
                        map,
                        CameraUpdateFactory.newLatLngZoom(boundsCoords.single(), 14.0),
                        getMapPaddingArray()
                    )
                }
            }
        }
    }

    /** @param chevronOnly when true (all-track mode), use only the chevron icon without the white circle. */
    private fun ensureArrowImageInStyle(style: Style, hexColor: String, chevronOnly: Boolean = false) {
        val suffix = hexColor.replace("#", "")
        val imageId = if (chevronOnly) "track-direction-arrow-simple-$suffix" else "track-direction-arrow-$suffix"
        if (style.getImage(imageId) != null) return
        val tintColor = parseHexToColor(hexColor, requireContext())
        val bitmap = if (chevronOnly) {
            MapMarkerUtils.getMarkerBitmapWithTintedForeground(
                requireContext(),
                R.drawable.ic_empty_32dp,
                R.drawable.ic_track_direction_arrow_chevron_fill,
                R.drawable.ic_track_direction_arrow_chevron_stroke_black,
                tintColor
            )
        } else {
            MapMarkerUtils.getMarkerBitmapWithTintedForeground(
                requireContext(),
                R.drawable.ic_track_direction_arrow_circle,
                R.drawable.ic_track_direction_arrow_chevron_fill,
                R.drawable.ic_track_direction_arrow_chevron_stroke,
                tintColor
            )
        }
        bitmap?.let {
            try {
                style.addImage(imageId, it)
            } catch (_: Exception) { }
        }
    }

    private fun applyInitialTargetTracker(
        initial: Tracker?,
        loadTrackerId: String,
        defaultTrackerId: String,
        prefs: android.content.SharedPreferences
    ) {
        // Set metadata and initial points immediately
        lastStreamedPointTimeMs = null
        lastStreamedAccuracyMeters = null
        if (initial != null) {
            displayedTrackerId = initial.id
            displayedTrackerName = initial.name
            displayedGroupName = null
            mapViewContext = if (initial.id != defaultTrackerId) MapViewContext.SPECIFIC_TRACKER else MapViewContext.DEFAULT_TRACKER
            lastCachedUpdateTimeMs = trackerLastUpdateMs(initial)
            currentTrackerColor = (initial.color ?: defaultTrackerColorHex(requireContext())).let { if (it.startsWith("#")) it else "#$it" }
            
            (initial.point_params?.lastOrNull()?.get("acc") as? Number)?.toFloat()?.takeIf { it > 0f }
                ?.let { lastStreamedAccuracyMeters = it }

            // Position camera using bbox or last_point; track line will be drawn by fetchFullGeometryAndApply
            val map = maplibreMap
            if (map != null) {
                val bbox = initial.bbox
                if (bbox != null && bbox.size == 4) {
                    val bounds = LatLngBounds.Builder()
                        .include(LatLng(bbox[1], bbox[0]))
                        .include(LatLng(bbox[3], bbox[2]))
                        .build()
                    moveCameraToFitBoundsWithMinZoomClamp(map, bounds)
                    zoomToTrackAfterLoad = false
                } else if (initial.last_point != null && initial.last_point.size >= 2) {
                    mapManager?.moveCameraWithPadding(map, CameraUpdateFactory.newLatLngZoom(
                        LatLng(initial.last_point[1], initial.last_point[0]), 14.0
                    ), getMapPaddingArray())
                    zoomToTrackAfterLoad = false
                }
            }
            updateZoomToLatestButtonState()
        } else {
            // Fallback to default
            displayedTrackerId = defaultTrackerId
            val defaultName = prefs.getString("selected_tracker_name", "") ?: ""
            displayedTrackerName = defaultName.ifEmpty { null }
            displayedGroupName = null
            mapViewContext = MapViewContext.DEFAULT_TRACKER
            lastCachedUpdateTimeMs = null
        }

        updateTrackerLabel()
        // Start streaming immediately — don't wait for full geometry to load
        startLiveTrackStreamingForDisplayedTracker()
        fetchFullGeometryAndApply(loadTrackerId)
    }

    /**
     * Refetch and redraw the selected tracker's track without moving the camera.
     */
    fun restoreTrackForSelectedTracker() {
        stopLiveTrackStreaming()
        showAllTrackers = false
        clearAllTrackSources()
        setAllTrackLayersVisibility(false)
        setAnnotationLayersVisibility(true)
        trackPoints.clear()
        trackTimestamps.clear()
        displayedTrackerId = null
        displayedTrackerName = null
        displayedGroupName = null
        mapViewContext = MapViewContext.DEFAULT_TRACKER
        restoreOnlyNoZoom = true
        followLockEnabled = false
        updateFollowLockButton()
        lastCachedUpdateTimeMs = null
        val prefs = requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
        val defaultId = prefs.getString("selected_tracker_id", "") ?: ""
        val defaultName = prefs.getString("selected_tracker_name", "") ?: ""
        if (defaultId.isEmpty()) {
            updateTrackLine()
            updateZoomToLatestButtonState()
            updateTrackerLabel()
            return
        }
        displayedTrackerId = defaultId
        displayedTrackerName = defaultName.ifEmpty { null }
        updateTrackerLabel()
        fetchHistory()
    }

    private fun fetchHistory() {
        if (mapViewContext == MapViewContext.GROUP) {
            updateZoomToLatestButtonState()
            updateTrackerLabel()
            return
        }
        if ((activity as? MainActivity)?.initialTrackForMap != null) {
            refreshTrackForSelectedTracker()
            return
        }
        
        val prefs = requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
        val defaultTrackerId = prefs.getString("selected_tracker_id", "") ?: ""
        
        // Use displayedTrackerId if it's already set (e.g. we just switched to a specific tracker)
        val trackerId = if (displayedTrackerId != null && displayedTrackerId!!.isNotEmpty()) {
            displayedTrackerId!!
        } else {
            defaultTrackerId
        }

        if (trackerId.isEmpty()) {
            updateZoomToLatestButtonState()
            updateTrackerLabel()
            return
        }
        // Zoom to default tracker extent on first load (e.g. app launch with Map tab) when we have no points yet
        if (trackerId == defaultTrackerId && trackPoints.isEmpty()) {
            zoomToTrackAfterLoad = true
        }
        fetchFullGeometryAndApply(trackerId)
    }

    private fun fetchFullGeometryAndApply(trackerId: String, forceReplace: Boolean = false) {
        geometryLoadingInProgress = true
        updateBottomRightSpinner(requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE).getString("selected_tracker_id", "") ?: "")
        TrackerRepository.getTrackerGeometry(requireContext(), trackerId) { tracker ->
            mainScope.launch {
                geometryLoadingInProgress = false
                updateBottomRightSpinner(requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE).getString("selected_tracker_id", "") ?: "")
                displayedTrackerId = trackerId
                displayedTrackerName = tracker?.name
                lastCachedUpdateTimeMs = trackerLastUpdateMs(tracker)
                val defaultId = requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
                    .getString("selected_tracker_id", "") ?: ""
                displayedGroupName = null
                mapViewContext = if (trackerId != defaultId) MapViewContext.SPECIFIC_TRACKER else MapViewContext.DEFAULT_TRACKER
                if (tracker != null) {
                    currentTrackerColor = (tracker.color ?: defaultTrackerColorHex(requireContext())).let { if (it.startsWith("#")) it else "#$it" }
                    if (trackerId != defaultId) {
                        (tracker.point_params?.lastOrNull()?.get("acc") as? Number)?.toFloat()?.takeIf { it > 0f }
                            ?.let { lastStreamedAccuracyMeters = it }
                    }
                }
                val coords = tracker?.geometry?.coordinates
                if (coords != null) {
                    val defaultId = requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
                        .getString("selected_tracker_id", "") ?: ""
                    val isExternalStreaming = !forceReplace && trackPoints.isNotEmpty() &&
                        displayedTrackerId != null && displayedTrackerId != defaultId

                    if (isExternalStreaming) {
                        // Merge: keep streaming points newer than geometry's latest timestamp
                        val lastCoords = coords.takeLast(TrackUpdateHelper.MAX_POINTS)
                        val geomLatestTs = lastCoords.lastOrNull()?.get(2)?.toLong() ?: Long.MAX_VALUE
                        val streamedAfterGeom = trackPoints.zip(trackTimestamps)
                            .filter { it.second > geomLatestTs }
                        trackPoints.clear()
                        trackTimestamps.clear()
                        trackPoints.addAll(lastCoords.map { LatLng(it[1], it[0]) })
                        trackTimestamps.addAll(lastCoords.map { it[2].toLong() })
                        for ((pt, ts) in streamedAfterGeom) {
                            TrackUpdateHelper.updateTrack(trackPoints, trackTimestamps, pt, ts)
                        }
                    } else if (forceReplace || trackPoints.isEmpty()) {
                        trackPoints.clear()
                        trackTimestamps.clear()
                        val lastCoords = coords.takeLast(TrackUpdateHelper.MAX_POINTS)
                        trackPoints.addAll(lastCoords.map { LatLng(it[1], it[0]) })
                        trackTimestamps.addAll(lastCoords.map { it[2].toLong() })
                    }
                    updateTrackLine()
                    setAnnotationLayersVisibility(true)
                    val map = maplibreMap
                    zoomToTrackAfterLoad = false
                    if (map != null && trackPoints.isNotEmpty()) {
                        val bbox = tracker?.bbox
                        if (bbox != null && bbox.size == 4) {
                            val bounds = LatLngBounds.Builder()
                                .include(LatLng(bbox[1], bbox[0]))
                                .include(LatLng(bbox[3], bbox[2]))
                                .build()
                            moveCameraToFitBoundsWithMinZoomClamp(map, bounds)
                        } else if (trackPoints.size >= 2) {
                            val bounds = LatLngBounds.Builder().apply {
                                trackPoints.forEach { include(it) }
                            }.build()
                            moveCameraToFitBoundsWithMinZoomClamp(map, bounds)
                        }
                    }
                    if (followLockEnabled && trackPoints.isNotEmpty()) {
                        centerCameraOnTrackLocked(trackPoints.last(), forceZoomIn = true)
                    }
                }
                restoreOnlyNoZoom = false
                updateZoomToLatestButtonState()
                updateTrackerLabel()
                startLiveTrackStreamingForDisplayedTracker()
            }
        }
    }

    /** Start live track streaming only when the displayed tracker is not the default. The default track is local (this device); only non-default tracks need server streaming. */
    private fun startLiveTrackStreamingForDisplayedTracker() {
        val id = displayedTrackerId ?: return
        val defaultId = requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
            .getString("selected_tracker_id", "") ?: ""
        if (defaultId.isEmpty() || id == defaultId) return
        val intent = Intent(requireContext(), LiveTrackStreamingService::class.java).apply {
            action = LiveTrackStreamingService.ACTION_START
            putExtra(LiveTrackStreamingService.EXTRA_TRACKER_ID, id)
            putExtra(LiveTrackStreamingService.EXTRA_TRACKER_NAME, displayedTrackerName)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun stopLiveTrackStreaming() {
        if (!LiveTrackStreamingService.isRunning) return
        val intent = Intent(requireContext(), LiveTrackStreamingService::class.java).apply {
            action = LiveTrackStreamingService.ACTION_STOP
        }
        requireContext().startService(intent)
    }

    private fun updateTrackLine() {
        val style = maplibreMap?.style ?: return
        val source = style.getSourceAs<GeoJsonSource>(TRACK_SOURCE_ID) ?: return
        
        val lineColor = currentTrackerColor ?: defaultTrackerColorHex(requireContext())
        if (trackPoints.size < 2) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            applyPositionSymbolUpdate()
            return
        }
        
        val outlineColorInt = ContextCompat.getColor(requireContext(), R.color.track_line_outline)
        val outlineColor = String.format("#%06X", 0xFFFFFF and outlineColorInt)
        val segments = splitTrackIntoSegments(trackPoints)
        
        val lineStrings = segments.map { segment ->
            LineString.fromLngLats(segment.map { org.maplibre.geojson.Point.fromLngLat(it.longitude, it.latitude) })
        }
        
        val multiLineString = MultiLineString.fromLineStrings(lineStrings)
        val feature = Feature.fromGeometry(multiLineString)
        feature.addStringProperty("outlineColor", outlineColor)
        feature.addStringProperty("lineColor", lineColor)
        
        source.setGeoJson(feature)
        applyPositionSymbolUpdate()
    }

    private fun applyPositionSymbolUpdate() {
        if (!isAdded) return
        val map = maplibreMap ?: return
        val style = map.style ?: return
        val source = style.getSourceAs<GeoJsonSource>(TRACK_POSITION_SOURCE_ID) ?: return
        val accuracySource = style.getSourceAs<GeoJsonSource>(TRACK_POSITION_ACCURACY_SOURCE_ID)
        
        if (trackPoints.isEmpty()) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            accuracySource?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return
        }
        
        val toLatLng = trackPoints.last()
        val toRotation = getTrackDirectionDegrees(trackPoints)
        val hexColor = currentTrackerColor ?: defaultTrackerColorHex(requireContext())
        
        val imageId = "track-direction-arrow-${hexColor.replace("#", "")}"
        var symbolIconId = imageId
        
        // Cache the directional arrow bitmap inside the MapLibre style instead of recreating it via Canvas every second
        if (style.getImage(imageId) == null) {
            val tintedBitmap = MapMarkerUtils.getMarkerBitmapWithTintedForeground(
                requireContext(),
                R.drawable.ic_track_direction_arrow_circle,
                R.drawable.ic_track_direction_arrow_chevron_fill,
                R.drawable.ic_track_direction_arrow_chevron_stroke,
                parseHexToColor(hexColor, requireContext())
            )
            if (tintedBitmap != null) {
                try {
                    style.addImage(imageId, tintedBitmap)
                } catch (_: Exception) { /* id may already exist gracefully */ }
            } else {
                symbolIconId = "track-direction-arrow" // Fallback name
            }
        }
        
        val prefs = requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
        val defaultTrackerId = prefs.getString("selected_tracker_id", "") ?: ""
        val showingDefault = displayedTrackerId == null || displayedTrackerId == defaultTrackerId
        val accuracyMeters = if (showingDefault) {
            TrackingService.lastAccuracyMeters
        } else {
            lastStreamedAccuracyMeters
        }
        val accuracyValue = (accuracyMeters?.takeIf { it > 0f } ?: 0f).toDouble()

        if (showingDefault) {
            val location = Location("tracker-default-location").apply {
                latitude = toLatLng.latitude
                longitude = toLatLng.longitude
                accuracy = accuracyValue.toFloat()
                bearing = toRotation
            }
            LocationComponentHelper.setEnabled(map, true)
            LocationComponentHelper.forceLocation(map, location)
            source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            accuracySource?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return
        }

        LocationComponentHelper.setEnabled(map, false)

        val point = Point.fromLngLat(toLatLng.longitude, toLatLng.latitude)
        val feature = Feature.fromGeometry(point)
        feature.addStringProperty("icon", symbolIconId)
        feature.addNumberProperty("rotate", toRotation)
        feature.addNumberProperty("accuracy", accuracyValue)

        source.setGeoJson(feature)
        if (accuracyValue > 0.0) {
            val circle = buildAccuracyPolygon(toLatLng, accuracyValue)
            val accuracyFeature = Feature.fromGeometry(circle)
            accuracySource?.setGeoJson(accuracyFeature)
        } else {
            accuracySource?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        }
    }

    /**
     * Build a geodesic polygon around [center] with [radiusMeters] radius.
     * Rendering this geometry avoids zoom-time CircleLayer radius jitter.
     */
    private fun buildAccuracyPolygon(center: LatLng, radiusMeters: Double, steps: Int = 64): Polygon {
        val earthRadiusMeters = 6378137.0
        val latRad = Math.toRadians(center.latitude)
        val lonRad = Math.toRadians(center.longitude)
        val angularDistance = radiusMeters / earthRadiusMeters
        val ring = ArrayList<Point>(steps + 1)
        for (i in 0..steps) {
            val bearing = (2.0 * Math.PI * i.toDouble()) / steps.toDouble()
            val sinLat = kotlin.math.sin(latRad)
            val cosLat = kotlin.math.cos(latRad)
            val sinAng = kotlin.math.sin(angularDistance)
            val cosAng = kotlin.math.cos(angularDistance)
            val sinLat2 = sinLat * cosAng + cosLat * sinAng * kotlin.math.cos(bearing)
            val lat2 = kotlin.math.asin(sinLat2)
            val y = kotlin.math.sin(bearing) * sinAng * cosLat
            val x = cosAng - sinLat * sinLat2
            var lon2 = lonRad + kotlin.math.atan2(y, x)
            lon2 = (lon2 + 3.0 * Math.PI) % (2.0 * Math.PI) - Math.PI
            ring.add(Point.fromLngLat(Math.toDegrees(lon2), Math.toDegrees(lat2)))
        }
        return Polygon.fromLngLats(listOf(ring))
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
        val results = FloatArray(3) // Hoist array allocation outside the up-to-1000 iteration loop!
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            Location.distanceBetween(prev.latitude, prev.longitude, curr.latitude, curr.longitude, results)
            val distanceMeters = results[0]
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
        /** Duration (ms) for animating the camera when follow lock is on and the track moves. */
        private const val FOLLOW_LOCK_ANIMATION_MS = 300
        /** Target zoom when enabling follow lock from a zoomed-out state. */
        private const val FOLLOW_LOCK_TARGET_ZOOM = 16.0
        /** Map cannot zoom out past this level (tracker map only). */
        private const val MIN_ZOOM = 1.0
        private const val MIN_ZOOM_EPSILON = 0.001
        /** Do not draw track across jumps larger than this (meters). 100 miles. */
        private const val MAX_JUMP_METERS = 100f * 1609.344f
        /** Content padding (dp) so overlays (name card, buttons, spinner) don't cut off the track. */
        private const val MAP_PADDING_LEFT_DP = 28
        private const val MAP_PADDING_TOP_DP = 130
        private const val MAP_PADDING_EDGE_EXTRA_DP = 12
        private const val MAP_PADDING_RIGHT_DP = 60
        private const val MAP_PADDING_BOTTOM_DP = 48

        // Map source/layer IDs (tracker map only)
        private const val TRACK_SOURCE_ID = "track-source"
        private const val TRACK_OUTLINE_LAYER_ID = "track-outline-layer"
        private const val TRACK_FILL_LAYER_ID = "track-fill-layer"
        private const val TRACK_POSITION_SOURCE_ID = "track-position-source"
        private const val TRACK_POSITION_ACCURACY_SOURCE_ID = "track-position-accuracy-source"
        private const val TRACK_POSITION_LAYER_ID = "track-position-layer"
        private const val TRACK_POSITION_ACCURACY_LAYER_ID = "track-position-accuracy-layer"

        private const val ALL_TRACKS_SOURCE_ID = "all-tracks-source"
        private const val ALL_TRACKS_POINTS_SOURCE_ID = "all-tracks-points-source"
        private const val ALL_TRACKS_OUTLINE_LAYER_ID = "all-tracks-outline-layer"
        private const val ALL_TRACKS_FILL_LAYER_ID = "all-tracks-fill-layer"
        private const val ALL_TRACKS_POINTS_LAYER_ID = "all-tracks-points-layer"
    }

    private enum class MapViewContext {
        DEFAULT_TRACKER,
        SPECIFIC_TRACKER,
        GROUP
    }
}
