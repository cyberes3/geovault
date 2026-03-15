package com.geovault.tracker.fragments

import android.annotation.SuppressLint
import android.graphics.Color
import android.content.*
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.view.Choreographer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.util.Log
import android.util.TypedValue
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
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.*
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdate
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
import org.maplibre.android.style.layers.TransitionOptions
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import android.graphics.PointF
import com.google.android.material.button.MaterialButton
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/** Selected tracker on the map (all-trackers or group mode); used for bottom info card. */
private data class SelectedMapTracker(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val lastUpdateMs: Long?,
    val isOwner: Boolean,
    val hexColor: String
)

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
    private lateinit var mapTrackerInfoCard: View
    private lateinit var mapTrackerInfoName: TextView
    private lateinit var mapTrackerInfoCoords: TextView
    private lateinit var mapTrackerInfoLastUpdated: TextView
    private lateinit var mapTrackerInfoViewParams: MaterialButton
    private lateinit var mapTrackerInfoViewInList: MaterialButton
    private lateinit var mapTrackerInfoZoomLock: ImageView
    private lateinit var showMyLocationButton: View
    private lateinit var showMyLocationButtonIcon: ImageView
    private lateinit var showMyLocationButtonLoading: LoadingSpinner

    /** When true, map shows all trackers; when false, single default/displayed tracker. */
    private var showAllTrackers = false

    /** True while fetchFullGeometryAndApply is in progress; used so bottom-right spinner stays visible if streaming starts. */
    private var geometryLoadingInProgress = false
    /** Tracker id currently loading via geometry API; used to coalesce duplicate lifecycle fetches. */
    private var geometryFetchInFlightTrackerId: String? = null

    /** Last streamed point timestamp (ms); only set when viewing a non-default track. Cleared when stopping streaming or switching tracker. */
    private var lastStreamedPointTimeMs: Long? = null
    /** Last streamed point accuracy (m); only set when viewing a non-default track and props.acc is sent. */
    private var lastStreamedAccuracyMeters: Float? = null
    /** Cached last-update time (ms) from loaded tracker/initial data; used to prefill "Updated" chip before first network point. */
    private var lastCachedUpdateTimeMs: Long? = null
    /** Per-tracker last-known update time (ms); used when reopening info box so we don't show "Waiting for data". */
    private val lastKnownUpdateTimeMsByTrackerId = mutableMapOf<String, Long>()
    /** Last selected tracker id we refreshed point icons for; skip refresh when only position/timestamp changed. */
    private var lastSelectedTrackerIdForIcons: String? = null

    private var mapReady = false
    private var followLockEnabled = false
    private var followLockNeedsInitialZoom = false
    /** When true, fetchHistory() will zoom the camera to fit the loaded track (e.g. after "View on map"). */
    private var zoomToTrackAfterLoad = false
    /** When true, fetchHistory() will not move the camera (restore track only). */
    private var restoreOnlyNoZoom = false
    /** Tracker currently shown (from initial or geometry load); used for "last updated" on first tap. */
    private var displayedTracker: Tracker? = null
    /** Id of the tracker currently shown on the map; used to show reset when viewing a non-default track. */
    private var displayedTrackerId: String? = null
    /** Name of the tracker currently shown on the map; used for the label in the upper left. */
    private var displayedTrackerName: String? = null
    /** Whether the displayed tracker is owned by the user; used for info card "View in list" label. */
    private var displayedTrackerIsOwner: Boolean = true
    /** Group currently shown on map, when in group context. */
    private var displayedGroupName: String? = null
    /** Explicit map UI context used for chip/button state. */
    private var mapViewContext: MapViewContext = MapViewContext.DEFAULT_TRACKER
    /** Dirty flag for debounced track line updates. */
    private var trackLineDirty = false
    /** When in group map context, the group being displayed (for "View in list" routing). */
    private var currentGroupForMap: Group? = null
    /** Active camera behavior intent used to keep padding/lifecycle moves deterministic. */
    private var activeCameraIntent: CameraIntent = CameraIntent.NONE
    /** Preserve centered all-trackers fit against later padding refresh drift. */
    private var preserveCenteredAllTrackersFit: Boolean = false
    /** Selected tracker from a map tap in all-trackers or group mode; null when none selected. */
    private var selectedMapTracker: SelectedMapTracker? = null
    /** When set, follow lock centers on this point (e.g. from info-panel crosshair); cleared on pan or clear selection. */
    private var lockTarget: LatLng? = null
    /** Active tracker ids currently requested from live streaming service. */
    private var activeStreamedTrackerIds: Set<String> = emptySet()
    /** In-memory history cache for multi-tracker map contexts (group/all-trackers). */
    private val multiTrackCoordsCache = mutableMapOf<String, MutableList<List<Double>>>()
    /** Debounced redraw job used to coalesce bursts of streamed multi-tracker points. */
    private var multiTrackRenderJob: Job? = null

    /** When true, user has enabled non-tracking "show my location" mode (blue dot, camera follow). */
    private var showMyLocationEnabled = false
    /** True after we have received at least one GPS fix while not tracking (used for button visibility). */
    private var hasLiveGpsFix = false
    /** Last location from standalone/probe callback; used to center camera and force location component. */
    private var lastStandaloneLocation: Location? = null
    /** True while location mode is enabled and we're waiting for the first fix. */
    private var waitingForStandaloneFix = false
    /** Arms one-time automatic recenter/zoom when the next fix arrives. */
    private var pendingAutoZoomToStandaloneFix = false
    /** When true, suppress GPS auto-recenter until user explicitly requests it via My Location button. */
    private var suppressStandaloneAutoZoom = false
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var standaloneLocationCallback: LocationCallback? = null

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
            val tsMs = intent.getLongExtra(LiveTrackStreamingService.EXTRA_POINT_TS_MS, 0L)
            if (intent.hasExtra(LiveTrackStreamingService.EXTRA_ACCURACY_METERS)) {
                lastStreamedAccuracyMeters = intent.getFloatExtra(LiveTrackStreamingService.EXTRA_ACCURACY_METERS, 0f).takeIf { it > 0f }
            }
            val lat = intent.getDoubleExtra(LiveTrackStreamingService.EXTRA_POINT_LAT, 0.0)
            val lon = intent.getDoubleExtra(LiveTrackStreamingService.EXTRA_POINT_LON, 0.0)
            applyLiveStreamPoint(trackId, lat, lon, tsMs.takeIf { it > 0L } ?: System.currentTimeMillis(), fromBuffered = false)
        }
    }

    private fun applyLiveStreamPoint(
        trackId: String,
        lat: Double,
        lon: Double,
        timestampMs: Long,
        fromBuffered: Boolean
    ) {
        val normalizedTimestampMs = normalizeTimestampToMs(timestampMs)
        val defaultTrackerId = requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
            .getString("selected_tracker_id", "") ?: ""
        val isMultiContext = showAllTrackers || mapViewContext == MapViewContext.GROUP
        if (isMultiContext) {
            if (trackId !in activeStreamedTrackerIds) return
            val trackers = lastAllTrackers ?: return
            val tracker = trackers.firstOrNull { it.id == trackId } ?: return
            val trackerCoords = getTrackerBaseCoordsForMultiContext(tracker, trackId)
            val accepted = appendStreamedPointIfNewer(trackerCoords, lon, lat, normalizedTimestampMs)
            if (!accepted) return
            multiTrackCoordsCache[trackId] = trackerCoords
            lastKnownUpdateTimeMsByTrackerId[trackId] = normalizedTimestampMs
            if (selectedMapTracker?.id == trackId) {
                selectedMapTracker = selectedMapTracker?.copy(
                    lat = lat,
                    lon = lon,
                    lastUpdateMs = normalizedTimestampMs
                )
                val updatedTarget = LatLng(lat, lon)
                if (!showMyLocationEnabled && isFollowLockActive()) {
                    lockTarget = updatedTarget
                    centerCameraOnTrackLocked(updatedTarget)
                }
            }
            scheduleDebouncedMultiTrackRender()
            if (selectedMapTracker?.id == trackId) {
                updateMapSelectionUi()
            }
            return
        }

        if (trackId != displayedTrackerId) return
        lastStreamedPointTimeMs = normalizedTimestampMs
        lastKnownUpdateTimeMsByTrackerId[trackId] = normalizedTimestampMs
        if (selectedMapTracker?.id == trackId) {
            selectedMapTracker = selectedMapTracker?.copy(
                lat = lat,
                lon = lon,
                lastUpdateMs = normalizedTimestampMs
            )
            updateMapSelectionUi()
        }
        if (isAdded) updateStreamingUi(defaultTrackerId)
        TrackUpdateHelper.updateTrack(trackPoints, trackTimestamps, LatLng(lat, lon), normalizedTimestampMs)
        scheduleTrackLineUpdate()
        updateZoomToLatestButtonState()
        if (!showMyLocationEnabled && isFollowLockActive()) {
            lockTarget = LatLng(lat, lon)
            centerCameraOnTrackLocked(lockTarget!!)
        }
    }

    private fun getTrackerBaseCoordsForMultiContext(tracker: Tracker, trackId: String): MutableList<List<Double>> {
        val cached = normalizeRawCoordinates(multiTrackCoordsCache[trackId] ?: emptyList())
        val lastRendered = normalizeRawCoordinates(lastAllTrackersCoordsById?.get(trackId) ?: emptyList())
        val geometry = normalizeRawCoordinates(tracker.geometry?.coordinates ?: emptyList())

        val historyBase = when {
            lastRendered.size >= geometry.size -> lastRendered
            else -> geometry
        }

        val base = when {
            cached.size >= historyBase.size && cached.isNotEmpty() -> cached
            historyBase.isNotEmpty() -> historyBase
            else -> seedCoordsFromLastPoint(tracker)
        }

        // If cache is shorter than loaded history (race while geometry is still fetching),
        // preserve history and append only newer live points from cache.
        if (cached.isNotEmpty() && base !== cached) {
            mergeNewerPointsInto(base, cached)
        }
        return base
    }

    private fun normalizeRawCoordinates(rawCoords: List<List<Double>>): MutableList<List<Double>> {
        val normalized = mutableListOf<List<Double>>()
        for (coord in rawCoords) {
            if (coord.size < 2) continue
            val lon = (coord[0] as? Number)?.toDouble() ?: continue
            val lat = (coord[1] as? Number)?.toDouble() ?: continue
            val tsRaw = (coord.getOrNull(2) as? Number)?.toDouble() ?: 0.0
            val tsMs = if (tsRaw in 1.0..999999999999.0) tsRaw * 1000.0 else tsRaw
            normalized.add(listOf(lon, lat, tsMs))
        }
        return normalized.takeLast(TrackUpdateHelper.MAX_POINTS).toMutableList()
    }

    private fun seedCoordsFromLastPoint(tracker: Tracker): MutableList<List<Double>> {
        val lp = tracker.last_point
        if (lp == null || lp.size < 2) return mutableListOf()
        val tsMs = trackerLastUpdateMs(tracker)?.toDouble() ?: 0.0
        return mutableListOf(listOf(lp[0], lp[1], tsMs))
    }

    private fun appendStreamedPointIfNewer(
        coords: MutableList<List<Double>>,
        lon: Double,
        lat: Double,
        timestampMs: Long
    ): Boolean {
        val normalizedTimestampMs = normalizeTimestampToMs(timestampMs)
        val last = coords.lastOrNull()
        if (last != null) {
            val lastTs = (last.getOrNull(2) as? Number)?.toLong() ?: 0L
            if (lastTs > 0L && normalizedTimestampMs > 0L && normalizedTimestampMs < lastTs) return false
            val lastLon = (last.getOrNull(0) as? Number)?.toDouble()
            val lastLat = (last.getOrNull(1) as? Number)?.toDouble()
            if (lastLon != null && lastLat != null && abs(lastLon - lon) < 1e-9 && abs(lastLat - lat) < 1e-9 && normalizedTimestampMs == lastTs) {
                return false
            }
        }
        coords.add(listOf(lon, lat, normalizedTimestampMs.toDouble()))
        while (coords.size > TrackUpdateHelper.MAX_POINTS) {
            coords.removeAt(0)
        }
        return true
    }

    private fun normalizeTimestampToMs(timestamp: Long): Long {
        return if (timestamp in 1L..999_999_999_999L) timestamp * 1000L else timestamp
    }

    private fun mergeNewerPointsInto(target: MutableList<List<Double>>, source: List<List<Double>>) {
        for (coord in source) {
            if (coord.size < 2) continue
            val lon = (coord.getOrNull(0) as? Number)?.toDouble() ?: continue
            val lat = (coord.getOrNull(1) as? Number)?.toDouble() ?: continue
            val ts = (coord.getOrNull(2) as? Number)?.toLong() ?: 0L
            appendStreamedPointIfNewer(target, lon, lat, ts)
        }
    }

    private fun scheduleDebouncedMultiTrackRender() {
        multiTrackRenderJob?.cancel()
        multiTrackRenderJob = mainScope.launch {
            delay(MULTI_TRACK_RENDER_DEBOUNCE_MS)
            if (!isAdded || !(showAllTrackers || mapViewContext == MapViewContext.GROUP)) return@launch
            val trackers = lastAllTrackers ?: return@launch
            val map = maplibreMap ?: return@launch
            val style = map.style ?: return@launch
            applyAllTrackersToMap(trackers, multiTrackCoordsCache.mapValues { it.value.toList() }, map, style, fitBounds = false)
        }
    }

    private fun clearMultiTrackContextState() {
        multiTrackRenderJob?.cancel()
        multiTrackRenderJob = null
        multiTrackCoordsCache.clear()
        lastAllTrackers = null
        lastAllTrackersCoordsById = null
        activeCameraIntent = CameraIntent.NONE
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
        mapTrackerInfoCard = view.findViewById(R.id.mapTrackerInfoCard)
        mapTrackerInfoName = view.findViewById(R.id.mapTrackerInfoName)
        mapTrackerInfoCoords = view.findViewById(R.id.mapTrackerInfoCoords)
        mapTrackerInfoLastUpdated = view.findViewById(R.id.mapTrackerInfoLastUpdated)
        mapTrackerInfoViewParams = view.findViewById(R.id.mapTrackerInfoViewParams)
        mapTrackerInfoViewInList = view.findViewById(R.id.mapTrackerInfoViewInList)
        mapTrackerInfoZoomLock = view.findViewById(R.id.mapTrackerInfoZoomLock)
        showMyLocationButton = view.findViewById(R.id.showMyLocationButton)
        showMyLocationButtonIcon = view.findViewById(R.id.showMyLocationButtonIcon)
        showMyLocationButtonLoading = view.findViewById(R.id.showMyLocationButtonLoading)
        showMyLocationButtonLoading.setTintColor(ContextCompat.getColor(requireContext(), R.color.content_on_primary))
        view.findViewById<View>(R.id.mapTrackerInfoFocus).setOnClickListener { onMapTrackerInfoFocus() }
        view.findViewById<View>(R.id.mapTrackerInfoClose).setOnClickListener { clearMapSelection() }
        mapTrackerInfoZoomLock.setOnClickListener { onMapTrackerInfoZoomLock() }
        mapTrackerInfoViewParams.setOnClickListener { onMapTrackerInfoViewParams() }
        mapTrackerInfoViewInList.setOnClickListener { onMapTrackerInfoViewInList() }

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
                // Disable MapLibre symbol placement fade so chevrons appear immediately.
                style.setTransition(TransitionOptions(0L, 0L, false))
                mgr.defaultPadding = getMapPaddingArray()
                val current = map.cameraPosition
                val padded = CameraPosition.Builder(current)
                    .padding(mgr.defaultPadding!!)
                    .build()
                applyUnifiedCameraMove(
                    map = map,
                    update = CameraUpdateFactory.newCameraPosition(padded),
                    paddingMode = CameraPaddingMode.OVERLAY_AWARE
                )
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
                style.addSource(
                    GeoJsonSource(
                        TRACK_SOURCE_ID,
                        GeoJsonOptions().apply { this["synchronousUpdate"] = true }
                    )
                )
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
                    // Render immediately when source updates; avoid icon fade-in after track draw.
                    setIconOpacityTransition(TransitionOptions(0L, 0L))
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
                    // Keep selected/unselected chevron swaps instantaneous.
                    setIconOpacityTransition(TransitionOptions(0L, 0L))
                }
                style.addLayer(allTracksOutlineLayer)
                style.addLayer(allTracksFillLayer)
                style.addLayer(allTracksPointsLayer)
                map.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                    override fun onMoveBegin(detector: org.maplibre.android.gestures.MoveGestureDetector) {
                        // User panned the map manually; clear sticky camera intent.
                        activeCameraIntent = CameraIntent.NONE
                        preserveCenteredAllTrackersFit = false
                        if (followLockEnabled) {
                            followLockEnabled = false
                            lockTarget = null
                            LocationComponentHelper.setCameraTracking(map, enabled = false)
                            updateFollowLockButton()
                            if (selectedMapTracker != null) updateMapSelectionUi()
                        }
                        // Pan does not turn off standalone location mode; user can recenter by tapping button again.
                    }
                    override fun onMove(detector: org.maplibre.android.gestures.MoveGestureDetector) { }
                    override fun onMoveEnd(detector: org.maplibre.android.gestures.MoveGestureDetector) { }
                })
                setupMapTapListener(map)
                mapReady = true
                mapLoadingOverlay.visibility = View.GONE
                refreshMapPaddingForCurrentMode(force = true)
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
            val main = activity as? MainActivity ?: return@setOnClickListener
            val group = currentGroupForMap
            if (group != null) {
                main.openGroupMembersAndScrollTo(group, displayedTrackerId)
            } else {
                main.openSharedAndScrollTo(displayedTrackerId)
            }
        }
        resetToTrackerButton.setOnClickListener {
            TrackerRepository.cancelGeometryRequest()
            restoreTrackForSelectedTracker()
        }

        followLockEnabled = savedInstanceState?.getBoolean(KEY_FOLLOW_LOCK, false) ?: false
        showMyLocationEnabled = savedInstanceState?.getBoolean(KEY_SHOW_MY_LOCATION, false) ?: false
        updateFollowLockButton()
        updateZoomToLatestButtonState()
        updateShowMyLocationButtonVisibility()

        requireActivity().supportFragmentManager.setFragmentResultListener(TrackersListFragment.REQUEST_REFRESH_LIST, viewLifecycleOwner) { _, bundle ->
            val hiddenId = bundle?.getString(TrackersListFragment.KEY_HIDDEN_TRACKER_ID)
            if (hiddenId != null) {
                if (hiddenId == displayedTrackerId) {
                    refreshTrackForSelectedTracker()
                }
                return@setFragmentResultListener
            }
            if (showAllTrackers && mapViewContext != MapViewContext.GROUP) {
                loadAllTrackersAndApply()
            }
        }

        mapToggle.setOnClickListener {
            val map = maplibreMap ?: return@setOnClickListener
            val mgr = mapManager ?: return@setOnClickListener
            mgr.sourceManager.setSelectedSourceId(mgr.sourceManager.getNextSourceId())
            mgr.applySelectedSource(map)
        }

        zoomToLatestButton.setOnClickListener {
            // Lock button is one-way: when already locked, tapping does nothing.
            // Unlocking is handled only by map interaction (pan/zoom gestures).
            if (isFollowLockActive()) return@setOnClickListener

            val target = lockTarget ?: trackPoints.lastOrNull()
            if (target != null) {
                lockTarget = target
                followLockEnabled = true
                followLockNeedsInitialZoom = true
                centerCameraOnTrackLocked(target, forceZoomIn = true)
            } else {
                followLockEnabled = false
                lockTarget = null
                followLockNeedsInitialZoom = false
            }
            updateFollowLockButton()
            if (selectedMapTracker != null) updateMapSelectionUi()
        }

        zoomInButton.setOnClickListener {
            maplibreMap?.let { map ->
                applyUnifiedCameraMove(
                    map = map,
                    update = CameraUpdateFactory.zoomBy(1.0),
                    paddingMode = zoomButtonsPaddingMode(),
                    animate = true,
                    durationMs = 200
                )
            }
        }
        zoomOutButton.setOnClickListener {
            maplibreMap?.let { map ->
                applyUnifiedCameraMove(
                    map = map,
                    update = CameraUpdateFactory.zoomBy(-1.0),
                    paddingMode = zoomButtonsPaddingMode(),
                    animate = true,
                    durationMs = 200
                )
            }
        }

        showMyLocationButton.setOnClickListener { onShowMyLocationClick() }
        showAllTrackersButton.setOnClickListener {
            if (showAllTrackers) {
                showAllTrackers = false
                stopLiveTrackStreaming()
                clearMultiTrackContextState()
                clearMapSelection()
                clearAllTrackSources()
                setAllTrackLayersVisibility(false)
                setAnnotationLayersVisibility(true)
                fetchHistory()
                updateTrackerLabel()
            } else {
                loadAllTrackersAndApply()
            }
        }
        view.post { if (isAdded) refreshMapPaddingForCurrentMode(force = true) }
    }

    override fun onResume() {
        super.onResume()
        view?.keepScreenOn = true
        updateTrackerLabel()
        refreshMapPaddingForCurrentMode(force = true)

        if (TrackingService.isRunning) {
            if (showMyLocationEnabled) {
                showMyLocationEnabled = false
                restoreTrackerLocationStyle()
                lastStandaloneLocation = null
                waitingForStandaloneFix = false
                pendingAutoZoomToStandaloneFix = false
            }
            stopStandaloneLocationUpdates(clearGpsFix = true)
        } else {
            if (showMyLocationEnabled) {
                waitingForStandaloneFix = true
                pendingAutoZoomToStandaloneFix = true
            }
            startStandaloneLocationUpdates()
        }

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
                applyLiveStreamPoint(
                    p.trackId,
                    p.lat,
                    p.lon,
                    p.timestampMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
                    fromBuffered = true
                )
                p.accuracyMeters?.let { lastStreamedAccuracyMeters = it }
            }
        }

        if (mapReady) {
            if (mapViewContext == MapViewContext.GROUP || showAllTrackers) {
                if (activeStreamedTrackerIds.isNotEmpty()) {
                    startLiveTrackStreamingForTrackerSet(activeStreamedTrackerIds)
                }
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
                    displayedTracker = null
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
        stopStandaloneLocationUpdates(clearGpsFix = true)
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
        geometryFetchInFlightTrackerId = null
        clearMultiTrackContextState()
        super.onDestroyView()
        mainScope.cancel()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_FOLLOW_LOCK, followLockEnabled)
        outState.putBoolean(KEY_SHOW_MY_LOCATION, showMyLocationEnabled)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapFragment?.mapView?.onLowMemory()
    }

    private fun isFollowLockActive(): Boolean = followLockEnabled && lockTarget != null

    private fun zoomButtonsPaddingMode(): CameraPaddingMode {
        val centeredIntent = activeCameraIntent == CameraIntent.GROUP_MEMBER_FOCUS ||
            activeCameraIntent == CameraIntent.SINGLE_TRACKER_FOCUS ||
            activeCameraIntent == CameraIntent.FOLLOW_LOCK
        return if (isFollowLockActive() || centeredIntent) {
            CameraPaddingMode.CENTERED
        } else {
            CameraPaddingMode.OVERLAY_AWARE
        }
    }

    private fun updateFollowLockButton() {
        if (isFollowLockActive()) {
            zoomToLatestButtonIcon.setImageResource(R.drawable.ic_crosshair_locked)
            zoomToLatestButtonIcon.contentDescription = getString(R.string.follow_lock_on_description)
        } else {
            zoomToLatestButtonIcon.setImageResource(R.drawable.ic_crosshair)
            zoomToLatestButtonIcon.contentDescription = getString(R.string.zoom_to_latest_description)
        }
        mapTrackerInfoZoomLock.setImageResource(
            if (isFollowLockActive()) R.drawable.ic_crosshair_locked else R.drawable.ic_crosshair
        )
    }

    private fun updateZoomToLatestButtonState() {
        val hasTrack = !showAllTrackers && trackPoints.isNotEmpty()
        zoomToLatestButton.visibility = if (hasTrack) View.VISIBLE else View.GONE
        updateRightStackMargins()
    }

    /** Right-stack buttons in top-to-bottom order. When a button is GONE, no space is reserved; visible buttons are re-stacked from top. */
    private fun updateRightStackMargins() {
        val ordered = listOf(
            zoomToLatestButton,
            showMyLocationButton,
            mapToggle,
            zoomInButton,
            zoomOutButton,
            showAllTrackersButton
        )
        val visible = ordered.filter { it.visibility == View.VISIBLE }
        val density = resources.displayMetrics.density
        val gapPx = (8 * density).toInt()
        val buttonHeightPx = (44 * density).toInt()
        val topDp = 16f
        val stepPx = gapPx + buttonHeightPx
        visible.forEachIndexed { index, v ->
            val params = v.layoutParams as? ViewGroup.MarginLayoutParams ?: return@forEachIndexed
            val topPx = (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, topDp, resources.displayMetrics) + index * stepPx).toInt()
            if (params.topMargin != topPx) {
                params.topMargin = topPx
                v.layoutParams = params
            }
        }
        // Stack visibility/layout updates should not move camera; only update manager/default padding.
        refreshMapPaddingForCurrentMode(force = true, allowCameraMove = false)
    }

    private fun updateShowMyLocationButtonVisibility() {
        // Keep button discoverable on blank maps: show whenever not tracking.
        // Disabled icon indicates "not yet locked"; spinner appears after user taps and we wait for first fix.
        val visible = !TrackingService.isRunning
        showMyLocationButton.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            val showLoading = showMyLocationEnabled && waitingForStandaloneFix
            if (showLoading) showMyLocationButtonLoading.show() else showMyLocationButtonLoading.hide()
            showMyLocationButtonIcon.visibility = if (showLoading) View.GONE else View.VISIBLE
            if (showLoading) {
                showMyLocationButtonIcon.contentDescription = getString(R.string.waiting_for_gps_lock)
            } else if (showMyLocationEnabled) {
                showMyLocationButtonIcon.setImageResource(R.drawable.ic_location_enabled)
                showMyLocationButtonIcon.contentDescription = getString(R.string.show_my_location_on_description)
            } else {
                showMyLocationButtonIcon.setImageResource(R.drawable.ic_location_disabled)
                showMyLocationButtonIcon.contentDescription = getString(R.string.show_my_location_description)
            }
        } else {
            showMyLocationButtonLoading.hide()
            showMyLocationButtonIcon.visibility = View.VISIBLE
        }
        updateRightStackMargins()
    }

    private fun zoomToStandaloneLocation(location: Location, forceZoomIn: Boolean = true, animate: Boolean = true) {
        val map = maplibreMap ?: return
        val targetZoom = if (forceZoomIn) maxOf(map.cameraPosition.zoom, FOLLOW_LOCK_TARGET_ZOOM) else map.cameraPosition.zoom
        val update = CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder()
                .target(LatLng(location.latitude, location.longitude))
                .zoom(targetZoom)
                .build()
        )
        applyUnifiedCameraMove(
            map = map,
            update = update,
            paddingMode = CameraPaddingMode.CENTERED,
            animate = animate,
            durationMs = FOLLOW_LOCK_ANIMATION_MS
        )
    }

    private fun isFreshStandaloneFix(location: Location): Boolean {
        val now = System.currentTimeMillis()
        return location.time > 0L && (now - location.time) <= STANDALONE_FIX_FRESHNESS_MS
    }

    @SuppressLint("MissingPermission")
    private fun onShowMyLocationClick() {
        val activity = activity as? MainActivity ?: return
        if (!activity.hasLocationPermission()) {
            activity.requestLocationPermission()
            return
        }
        // GPS recenter is independent from track follow-lock; clear lock UI/state.
        if (followLockEnabled) {
            followLockEnabled = false
            lockTarget = null
            maplibreMap?.let { LocationComponentHelper.setCameraTracking(it, enabled = false) }
            updateFollowLockButton()
            if (selectedMapTracker != null) updateMapSelectionUi()
            // Re-arm normal overlay-aware padding, but do not move camera yet.
            // GPS recenter below should be the single camera move to avoid lock->gps offset races.
            refreshMapPaddingForCurrentMode(force = true, allowCameraMove = false)
        }
        if (showMyLocationEnabled) {
            suppressStandaloneAutoZoom = false
            lastStandaloneLocation?.let { loc ->
                zoomToStandaloneLocation(loc, forceZoomIn = true)
            }
            return
        }
        suppressStandaloneAutoZoom = false
        showMyLocationEnabled = true
        applyStandaloneLocationStyle()
        stopStandaloneLocationUpdates(clearGpsFix = false)
        // Always wait for a fresh live callback fix after enabling location mode.
        // This guarantees the button shows a spinner and auto-zooms exactly once when fix arrives.
        waitingForStandaloneFix = true
        pendingAutoZoomToStandaloneFix = true
        startStandaloneLocationUpdates()
        updateShowMyLocationButtonVisibility()
    }

    /** Prevent one-shot GPS recenter from racing explicit tracker-focus camera moves. */
    private fun suppressStandaloneAutoZoomForTrackerFocus() {
        suppressStandaloneAutoZoom = true
        waitingForStandaloneFix = false
        pendingAutoZoomToStandaloneFix = false
        updateShowMyLocationButtonVisibility()
    }

    private fun isTrackerFocusIntentActive(): Boolean {
        return activeCameraIntent == CameraIntent.SINGLE_TRACKER_FOCUS ||
            activeCameraIntent == CameraIntent.GROUP_MEMBER_FOCUS
    }

    /** Apply blue/white/black circle marker for standalone "my location" mode. */
    private fun applyStandaloneLocationStyle() {
        val map = maplibreMap ?: return
        LocationComponentHelper.applyStyle(
            map,
            requireContext(),
            LocationComponentHelper.Config(
                accuracyColor = parseHexToColor(null, requireContext()),
                accuracyAlpha = 0.25f,
                backgroundDrawable = R.drawable.ic_my_location_marker,
                foregroundDrawable = R.drawable.ic_my_location_marker,
                renderMode = RenderMode.NORMAL
            )
        )
    }

    /** Restore tracker arrow/circle style when leaving standalone mode. */
    private fun restoreTrackerLocationStyle() {
        val map = maplibreMap ?: return
        LocationComponentHelper.applyStyle(
            map,
            requireContext(),
            LocationComponentHelper.Config(
                accuracyColor = parseHexToColor(null, requireContext()),
                accuracyAlpha = 0.25f,
                backgroundDrawable = R.drawable.ic_track_direction_arrow_circle,
                foregroundDrawable = R.drawable.ic_track_direction_arrow,
                renderMode = RenderMode.COMPASS
            )
        )
        // Restore normal position display (track or hidden)
        updateTrackLine()
    }

    /** Start location updates when not tracking: provides hasLiveGpsFix for button visibility and, when showMyLocationEnabled, updates map. */
    @SuppressLint("MissingPermission")
    private fun startStandaloneLocationUpdates() {
        if (TrackingService.isRunning) return
        val activity = activity as? MainActivity ?: return
        if (!activity.hasLocationPermission()) return
        if (fusedLocationClient == null) {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireContext())
        }
        val client = fusedLocationClient ?: return
        if (standaloneLocationCallback != null) return
        val intervalMs = if (showMyLocationEnabled) 3000L else 10_000L
        val request = LocationRequest.Builder(
            if (showMyLocationEnabled) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_LOW_POWER,
            intervalMs
        ).apply {
            if (showMyLocationEnabled) {
                setMinUpdateIntervalMillis(2000L)
                setMinUpdateDistanceMeters(5f)
            }
        }.build()
        standaloneLocationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                if (!isAdded) return
                hasLiveGpsFix = true
                lastStandaloneLocation = location
                if (showMyLocationEnabled) {
                    waitingForStandaloneFix = false
                    maplibreMap?.let { map ->
                        LocationComponentHelper.setEnabled(map, true)
                        LocationComponentHelper.forceLocation(map, location)
                    }
                    if (pendingAutoZoomToStandaloneFix && !isTrackerFocusIntentActive() && !suppressStandaloneAutoZoom) {
                        zoomToStandaloneLocation(location, forceZoomIn = true, animate = true)
                        pendingAutoZoomToStandaloneFix = false
                    }
                }
                updateShowMyLocationButtonVisibility()
            }
        }
        client.requestLocationUpdates(request, standaloneLocationCallback!!, Looper.getMainLooper())
        client.lastLocation.addOnSuccessListener { location ->
            if (location != null && isAdded) {
                lastStandaloneLocation = location
                if (showMyLocationEnabled) {
                    if (isFreshStandaloneFix(location)) {
                        waitingForStandaloneFix = false
                        maplibreMap?.let { map ->
                            LocationComponentHelper.setEnabled(map, true)
                            LocationComponentHelper.forceLocation(map, location)
                        }
                        if (pendingAutoZoomToStandaloneFix && !isTrackerFocusIntentActive() && !suppressStandaloneAutoZoom) {
                            zoomToStandaloneLocation(location, forceZoomIn = true, animate = true)
                            pendingAutoZoomToStandaloneFix = false
                        }
                    }
                }
                updateShowMyLocationButtonVisibility()
            }
        }
    }

    private fun stopStandaloneLocationUpdates(clearGpsFix: Boolean = true) {
        standaloneLocationCallback?.let { callback ->
            fusedLocationClient?.removeLocationUpdates(callback)
            standaloneLocationCallback = null
        }
        if (clearGpsFix) {
            hasLiveGpsFix = false
            lastStandaloneLocation = null
            waitingForStandaloneFix = false
            pendingAutoZoomToStandaloneFix = false
            suppressStandaloneAutoZoom = false
        }
        val map = maplibreMap
        if (map != null) {
            LocationComponentHelper.setCameraTracking(map, enabled = false)
            if (!showMyLocationEnabled) {
                LocationComponentHelper.setEnabled(map, false)
            }
        }
        updateShowMyLocationButtonVisibility()
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
            updateRightStackMargins()
            return
        }
        val showingSpecificTracker = !showAllTrackers &&
            mapViewContext == MapViewContext.SPECIFIC_TRACKER &&
            !displayedTrackerId.isNullOrEmpty()
        if (defaultTrackerId.isEmpty() && !showingSpecificTracker) {
            trackerLabelCard.visibility = View.GONE
            displayedTracker = null
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
        updateRightStackMargins()
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

    /** Extract last update timestamp (ms) from tracker geometry, last_point, or updated_at; same convention as TrackersListFragment. */
    private fun trackerLastUpdateMs(tracker: Tracker?): Long? {
        if (tracker == null) return null
        val coord = tracker.geometry?.coordinates?.lastOrNull() ?: tracker.last_point
        if (coord != null && coord.size >= 3) {
            val t = (coord[2] as? Number)?.toLong() ?: return null
            return if (t < 1e12) t * 1000 else t
        }
        // Fallback to list/API updated_at so we never show "Waiting for data" when we have cached data
        val u = tracker.updated_at ?: return null
        return if (u < 1e12) u * 1000 else u
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
            if (!showMyLocationEnabled && isFollowLockActive()) {
                lockTarget = latLng
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
        val zoom = if (shouldForceZoom) {
            maxOf(map.cameraPosition.zoom, FOLLOW_LOCK_TARGET_ZOOM)
        } else {
            map.cameraPosition.zoom
        }
        val update = CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder().target(target).zoom(zoom).build()
        )
        val callback = if (shouldForceZoom) {
            object : MapLibreMap.CancelableCallback {
                override fun onFinish() {
                    if (!isAdded) return
                    val reachedTarget = map.cameraPosition.zoom >= (FOLLOW_LOCK_TARGET_ZOOM - FOLLOW_LOCK_TARGET_ZOOM_EPSILON)
                    if (reachedTarget) followLockNeedsInitialZoom = false
                }

                override fun onCancel() {
                    // Keep initial-zoom request armed so the next lock update can complete it.
                }
            }
        } else {
            null
        }
        applyUnifiedCameraMove(
            map = map,
            update = update,
            paddingMode = CameraPaddingMode.CENTERED,
            intent = CameraIntent.FOLLOW_LOCK,
            animate = true,
            durationMs = FOLLOW_LOCK_ANIMATION_MS,
            callback = callback
        )
    }

    /**
     * Single camera entrypoint for all zoom/focus/lock actions.
     * Every camera move should go through this so padding behavior stays consistent.
     */
    private fun applyUnifiedCameraMove(
        map: MapLibreMap,
        update: CameraUpdate,
        paddingMode: CameraPaddingMode,
        intent: CameraIntent? = null,
        animate: Boolean = false,
        durationMs: Int = FOLLOW_LOCK_ANIMATION_MS,
        callback: MapLibreMap.CancelableCallback? = null
    ) {
        val mgr = mapManager ?: return
        intent?.let { activeCameraIntent = it }
        val padding = when (paddingMode) {
            CameraPaddingMode.CENTERED -> FOLLOW_LOCK_PADDING
            CameraPaddingMode.OVERLAY_AWARE -> getMapPaddingArray()
        }
        if (animate) {
            mgr.animateCameraWithPadding(map, update, padding, durationMs, callback)
        } else {
            mgr.moveCameraWithPadding(map, update, padding)
        }
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
            showMyLocationButton,
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
            listOf(zoomToLatestButton, showMyLocationButton, mapToggle, zoomInButton, zoomOutButton, showAllTrackersButton)
                .filter { it.visibility == View.VISIBLE }
                .maxOfOrNull { (mapWidthPx - it.left).coerceAtLeast(0) }
                ?: 0
        } else 0
        val rightPaddingPx = maxOf(
            baseRightPx,
            if (rightOverlayInsetPx > 0) rightOverlayInsetPx + extraPadPx else baseRightPx
        )
        val bottomOverlayInsetPx = if (mapHeightPx > 0) {
            val spinnerInset = if (geometryLoadingSpinner.visibility == View.VISIBLE) {
                (mapHeightPx - geometryLoadingSpinner.top).coerceAtLeast(0)
            } else 0
            val infoCardInset = if (mapTrackerInfoCard.visibility == View.VISIBLE) {
                if (mapTrackerInfoCard.height > 0) (mapHeightPx - mapTrackerInfoCard.top).coerceAtLeast(0)
                else (MAP_TRACKER_INFO_CARD_HEIGHT_DP * density).toInt() + (16 * density).toInt() * 2
            } else 0
            maxOf(spinnerInset, infoCardInset)
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
        val p = getBoundsPaddingEdgesPx(0)
        val boundsUpdate = CameraUpdateFactory.newLatLngBounds(bounds, p[0], p[1], p[2], p[3])
        val pos = boundsUpdate.getCameraPosition(map)
        if (pos != null && pos.zoom.toDouble() >= MIN_ZOOM) {
            applyUnifiedCameraMove(
                map = map,
                update = boundsUpdate,
                paddingMode = CameraPaddingMode.OVERLAY_AWARE
            )
        } else {
            val center = bounds.center
            applyUnifiedCameraMove(
                map = map,
                update = CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(center)
                        .zoom(MIN_ZOOM)
                        .tilt(0.0)
                        .bearing(0.0)
                        .build()
                ),
                paddingMode = CameraPaddingMode.OVERLAY_AWARE
            )
        }
    }

    /**
     * Same as moveCameraToFitBoundsWithMinZoomClamp, but with zero camera padding so the
     * fitted target stays visually centered on screen (used for group-member tap zoom).
     */
    private fun moveCameraToFitBoundsCenteredWithMinZoomClamp(map: MapLibreMap, bounds: LatLngBounds) {
        val boundsUpdate = CameraUpdateFactory.newLatLngBounds(bounds, 0, 0, 0, 0)
        val pos = boundsUpdate.getCameraPosition(map)
        if (pos != null && pos.zoom.toDouble() >= MIN_ZOOM) {
            applyUnifiedCameraMove(
                map = map,
                update = boundsUpdate,
                paddingMode = CameraPaddingMode.CENTERED
            )
        } else {
            val center = bounds.center
            applyUnifiedCameraMove(
                map = map,
                update = CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(center)
                        .zoom(MIN_ZOOM)
                        .tilt(0.0)
                        .bearing(0.0)
                        .build()
                ),
                paddingMode = CameraPaddingMode.CENTERED
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
        if (fitToTrackerId != null) {
            preserveCenteredAllTrackersFit = false
            val selectedTrackerPoint = coordsByTrackerId[fitToTrackerId]?.lastOrNull()
                ?: trackers.firstOrNull { it.id == fitToTrackerId }?.last_point
                    ?.takeIf { it.size >= 2 }
                    ?.let { lp -> LatLng(lp[1], lp[0]) }
            Log.d(
                TAG,
                "all-trackers fit specific tracker path: fitToTrackerId=$fitToTrackerId, hasSelectedPoint=${selectedTrackerPoint != null}"
            )
            if (selectedTrackerPoint != null) {
                applyUnifiedCameraMove(
                    map = map,
                    update = CameraUpdateFactory.newLatLngZoom(selectedTrackerPoint, TRACKER_CARD_FOCUS_ZOOM),
                    paddingMode = CameraPaddingMode.CENTERED,
                    intent = CameraIntent.GROUP_MEMBER_FOCUS
                )
            } else {
                moveCameraToFitBoundsCenteredWithMinZoomClamp(map, bounds)
            }
            return
        }
        val mgr = mapManager ?: return
        val repTrackerPoints = trackers.mapNotNull { t ->
            coordsByTrackerId[t.id]?.lastOrNull()?.let { t.id to it }
        }
        val p = getBoundsPaddingEdgesPx(0)
        val fitPaddingMode = CameraPaddingMode.OVERLAY_AWARE
        val boundsUpdate = CameraUpdateFactory.newLatLngBounds(bounds, p[0], p[1], p[2], p[3])
        val pos = boundsUpdate.getCameraPosition(map)
        // If fit computes exactly MIN_ZOOM, that can still mean "clamped from lower zoom";
        // in that case we should run fit-most instead of assuming all points fit.
        if (pos != null && pos.zoom.toDouble() > (MIN_ZOOM + MIN_ZOOM_EPSILON)) {
            preserveCenteredAllTrackersFit = false
            Log.d(
                TAG,
                "all-trackers fit all: zoom=${pos.zoom}, minZoom=$MIN_ZOOM, trackerCount=${trackers.size}"
            )
            applyUnifiedCameraMove(
                map = map,
                update = boundsUpdate,
                paddingMode = fitPaddingMode,
                intent = CameraIntent.BOUNDS_FIT
            )
            return
        }
        if (repTrackerPoints.size <= 1) {
            preserveCenteredAllTrackersFit = false
            val target = repTrackerPoints.firstOrNull()?.second
                ?: trackers.firstNotNullOfOrNull { t -> coordsByTrackerId[t.id]?.lastOrNull() }
                ?: bounds.center
            Log.d(
                TAG,
                "all-trackers fallback(single): representativeTrackerCount=${repTrackerPoints.size}, minZoom=$MIN_ZOOM, target=(${target.latitude},${target.longitude})"
            )
            applyUnifiedCameraMove(
                map = map,
                update = CameraUpdateFactory.newLatLngZoom(target, MIN_ZOOM),
                paddingMode = fitPaddingMode,
                intent = CameraIntent.BOUNDS_FIT
            )
            return
        }
        preserveCenteredAllTrackersFit = true
        val visibleW = (map.width - p[0] - p[2]).coerceAtLeast(1f).toDouble()
        val visibleH = (map.height - p[1] - p[3]).coerceAtLeast(1f).toDouble()
        val halfW = visibleW * 0.5
        val halfH = visibleH * 0.5
        val worldSize = 256.0 * 2.0.pow(MIN_ZOOM)

        val pointsProjected = repTrackerPoints.map { (trackerId, pt) ->
            ProjectedTrackerPoint(
                trackerId = trackerId,
                latitude = pt.latitude,
                longitude = pt.longitude,
                worldX = worldXAtZoom(pt.longitude, MIN_ZOOM),
                worldY = worldYAtZoom(pt.latitude, MIN_ZOOM)
            )
        }
        val center = bounds.center
        val selection = BestEffortViewportSelector.select(
            points = pointsProjected,
            worldSize = worldSize,
            halfWidthPx = halfW,
            halfHeightPx = halfH,
            preferredCenterX = worldXAtZoom(center.longitude, MIN_ZOOM),
            preferredCenterY = worldYAtZoom(center.latitude, MIN_ZOOM)
        )
        val best = selection ?: run {
            preserveCenteredAllTrackersFit = false
            val fallbackTarget = repTrackerPoints.first().second
            applyUnifiedCameraMove(
                map = map,
                update = CameraUpdateFactory.newLatLngZoom(fallbackTarget, MIN_ZOOM),
                paddingMode = fitPaddingMode,
                intent = CameraIntent.BOUNDS_FIT
            )
            return
        }
        val bestCenter = LatLng(
            worldYToLatDeg(best.centerY, worldSize),
            worldXToLonDeg(best.centerX, worldSize)
        )
        val includedTrackerIds = best.includedTrackerIds
        val bestCount = includedTrackerIds.size
        val excludedTrackerIds = repTrackerPoints.map { it.first }.filterNot { includedTrackerIds.contains(it) }
        Log.d(
            TAG,
            "all-trackers fit-most: total=${repTrackerPoints.size}, included=${includedTrackerIds.size}, excluded=${excludedTrackerIds.size}, minZoom=$MIN_ZOOM, visiblePx=(${visibleW.toInt()}x${visibleH.toInt()}), center=(${bestCenter.latitude},${bestCenter.longitude}), tieBreak=${best.tieBreakReason}, extentArea=${best.extentArea}, includedIds=$includedTrackerIds, excludedIds=$excludedTrackerIds"
        )
        if (bestCount <= 1) {
            preserveCenteredAllTrackersFit = false
            val ordered = trackers.mapNotNull { t -> coordsByTrackerId[t.id]?.lastOrNull() }
            val target = ordered.firstOrNull() ?: repTrackerPoints.first().second
            Log.d(
                TAG,
                "all-trackers fallback(one-at-min-zoom): bestCount=$bestCount, minZoom=$MIN_ZOOM, target=(${target.latitude},${target.longitude})"
            )
            applyUnifiedCameraMove(
                map = map,
                update = CameraUpdateFactory.newLatLngZoom(target, MIN_ZOOM),
                paddingMode = fitPaddingMode,
                intent = CameraIntent.BOUNDS_FIT
            )
        } else {
            applyUnifiedCameraMove(
                map = map,
                update = CameraUpdateFactory.newLatLngZoom(bestCenter, MIN_ZOOM),
                paddingMode = fitPaddingMode,
                intent = CameraIntent.BOUNDS_FIT
            )
        }
    }

    /**
     * Survey-style padding refresh: store default padding and optionally apply it
     * to the current camera position so UI inset changes take effect immediately.
     */
    private fun refreshMapPadding(force: Boolean = false, applyToCamera: Boolean = true) {
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
        if (!applyToCamera) return
        if (!force && isSamePadding) return
        val padded = CameraPosition.Builder(map.cameraPosition)
            .padding(targetPadding)
            .build()
        applyUnifiedCameraMove(
            map = map,
            update = CameraUpdateFactory.newCameraPosition(padded),
            paddingMode = CameraPaddingMode.OVERLAY_AWARE
        )
    }

    /**
     * Centralized padding refresh policy:
     * - Always refresh manager/default padding.
     * - Do not move camera while follow lock is active unless explicitly allowed.
     */
    private fun refreshMapPaddingForCurrentMode(force: Boolean = false, allowCameraMove: Boolean = true) {
        val preserveCenteredGroupFocus = activeCameraIntent == CameraIntent.GROUP_MEMBER_FOCUS
        refreshMapPadding(
            force = force,
            applyToCamera = allowCameraMove &&
                !isFollowLockActive() &&
                !preserveCenteredGroupFocus &&
                !(showAllTrackers && activeCameraIntent == CameraIntent.BOUNDS_FIT && preserveCenteredAllTrackersFit)
        )
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
        clearMultiTrackContextState()
        activeCameraIntent = CameraIntent.SINGLE_TRACKER_FOCUS
        suppressStandaloneAutoZoomForTrackerFocus()
        showAllTrackers = false
        displayedGroupName = null
        currentGroupForMap = null
        clearMapSelection()
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
            displayedTracker = null
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
        clearMultiTrackContextState()
        val map = maplibreMap ?: return
        val style = map.style ?: return
        mapViewContext = MapViewContext.GROUP
        displayedGroupName = group.name
        currentGroupForMap = group
        activeCameraIntent = if (zoomToTrackerId.isNullOrBlank()) CameraIntent.BOUNDS_FIT else CameraIntent.GROUP_MEMBER_FOCUS
        if (!zoomToTrackerId.isNullOrBlank()) {
            suppressStandaloneAutoZoomForTrackerFocus()
        }
        showAllTrackers = true
        clearMapSelection()
        clearAllTrackSources()
        setAllTrackLayersVisibility(false)
        setAnnotationLayersVisibility(false)
        trackPoints.clear()
        trackTimestamps.clear()
        updateTrackLine()
        displayedTracker = null
        displayedTrackerId = null
        displayedTrackerName = null
        stopLiveTrackStreaming()
        updateTrackerLabel()
        updateZoomToLatestButtonState()
        val trackIds = group.track_ids?.toSet() ?: emptySet()
        if (trackIds.isEmpty()) {
            stopLiveTrackStreaming()
            setAllTrackLayersVisibility(true)
            return
        }
        TrackerRepository.getTrackers(requireContext(), forceRefresh = false) { list ->
            if (!isAdded) return@getTrackers
            val allTrackers = list ?: emptyList()
            val trackers = allTrackers.filter { it.id in trackIds }
            if (trackers.isEmpty()) {
                stopLiveTrackStreaming()
                setAllTrackLayersVisibility(true)
                return@getTrackers
            }
            startLiveTrackStreamingForTrackerSet(trackers.map { it.id }.toSet())
            applyAllTrackersToMap(trackers, emptyMap(), map, style, fitBounds = true, fitToTrackerId = zoomToTrackerId)
            TrackerRepository.getTrackersGeometry(
                requireContext(),
                trackers.map { it.id },
                allData = true
            ) { fullTrackers ->
                mainScope.launch {
                    if (!isAdded || !showAllTrackers) return@launch
                    val coordsById = mutableMapOf<String, List<List<Double>>>()
                    (fullTrackers ?: emptyList()).forEach { full ->
                        val coords = full.geometry?.coordinates ?: emptyList()
                        if (coords.isNotEmpty()) {
                            coordsById[full.id] = coords
                        }
                    }
                    applyAllTrackersToMap(
                        trackers,
                        coordsById,
                        map,
                        style,
                        // Keep camera stable after initial fit; full-geometry load should only enrich lines.
                        fitBounds = false,
                        fitToTrackerId = zoomToTrackerId
                    )
                }
            }
        }
    }

    private fun loadAllTrackersAndApply() {
        clearMultiTrackContextState()
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
                        stopLiveTrackStreaming()
                        showAllTrackers = true
                        clearAllTrackSources()
                        setAllTrackLayersVisibility(true)
                        setAnnotationLayersVisibility(false)
                        updateTrackerLabel()
                        updateZoomToLatestButtonState()
                        return@getTrackers
                    }
                    showAllTrackers = true
                    startLiveTrackStreamingForTrackerSet(trackers.map { it.id }.toSet())
                    applyAllTrackersToMap(trackers, emptyMap(), map, style, fitBounds = true)
                    TrackerRepository.getTrackersGeometry(
                        requireContext(),
                        trackers.map { it.id },
                        allData = true
                    ) { fullTrackers ->
                        mainScope.launch {
                            if (!isAdded || !showAllTrackers) return@launch
                            val coordsById = mutableMapOf<String, List<List<Double>>>()
                            (fullTrackers ?: emptyList()).forEach { full ->
                                val coords = full.geometry?.coordinates ?: emptyList()
                                if (coords.isNotEmpty()) {
                                    coordsById[full.id] = coords
                                }
                            }
                            applyAllTrackersToMap(
                                trackers,
                                coordsById,
                                map,
                                style,
                                // Keep camera stable after initial fit; full-geometry load should only enrich lines.
                                fitBounds = false
                            )
                        }
                    }
                }
            }
        }
    }

    /** Cached for refreshing point icons when selection changes (all-trackers or group map). */
    private var lastAllTrackers: List<Tracker>? = null
    private var lastAllTrackersCoordsById: Map<String, List<List<Double>>>? = null

    private fun applyAllTrackersToMap(
        trackers: List<Tracker>,
        coordsById: Map<String, List<List<Double>>>,
        map: MapLibreMap,
        style: Style,
        fitBounds: Boolean,
        fitToTrackerId: String? = null
    ) {
        lastAllTrackers = trackers
        val normalizedCoordsById = mutableMapOf<String, MutableList<List<Double>>>()
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
                    normalizedCoordsById[tracker.id] = seedCoordsFromLastPoint(tracker)
                    val pt = LatLng(lp[1], lp[0])
                    allCoords.add(pt)
                    trackerCoords.add(pt)
                    val hexColor = tracker.color?.let { if (it.startsWith("#")) it else "#$it" } ?: defaultColor
                    val isSelected = tracker.id == selectedMapTracker?.id
                    ensureArrowImageInStyle(style, hexColor, chevronOnly = !isSelected)
                    val suffix = hexColor.replace("#", "")
                    val imageId = if (isSelected) "track-direction-arrow-$suffix" else "track-direction-arrow-simple-$suffix"
                    val pointFeature = Feature.fromGeometry(Point.fromLngLat(lp[0], lp[1]))
                    pointFeature.addStringProperty("icon", imageId)
                    addTrackerPropertiesToPointFeature(pointFeature, tracker, pt.latitude, pt.longitude)
                    pointFeatures.add(pointFeature)
                }
                coordsByTrackerId[tracker.id] = trackerCoords
                continue
            }
            normalizedCoordsById[tracker.id] = normalizeRawCoordinates(coords)
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
            val isSelected = tracker.id == selectedMapTracker?.id
            ensureArrowImageInStyle(style, hexColor, chevronOnly = !isSelected)
            val suffix = hexColor.replace("#", "")
            val imageId = if (isSelected) "track-direction-arrow-$suffix" else "track-direction-arrow-simple-$suffix"
            val pointFeature = Feature.fromGeometry(Point.fromLngLat(lastPoint.longitude, lastPoint.latitude))
            pointFeature.addStringProperty("icon", imageId)
            pointFeature.addNumberProperty("rotate", rotation.toDouble())
            val lastUpdateMs = lastN.lastOrNull()?.get(2)?.let { t -> (t as? Number)?.toLong()?.let { n -> if (n < 1e12) n * 1000 else n } }
            addTrackerPropertiesToPointFeature(pointFeature, tracker, lastPoint.latitude, lastPoint.longitude, lastUpdateMs)
            pointFeatures.add(pointFeature)
        }
        multiTrackCoordsCache.clear()
        multiTrackCoordsCache.putAll(normalizedCoordsById)
        lastAllTrackersCoordsById = normalizedCoordsById.mapValues { it.value.toList() }

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
                    val singlePointPaddingMode = if (fitToTrackerId != null) {
                        CameraPaddingMode.CENTERED
                    } else {
                        CameraPaddingMode.OVERLAY_AWARE
                    }
                    applyUnifiedCameraMove(
                        map = map,
                        update = CameraUpdateFactory.newLatLngZoom(boundsCoords.single(), 14.0),
                        paddingMode = singlePointPaddingMode,
                        intent = if (fitToTrackerId != null) CameraIntent.GROUP_MEMBER_FOCUS else CameraIntent.BOUNDS_FIT
                    )
                }
            }
        }
    }

    private fun setupMapTapListener(map: MapLibreMap) {
        map.addOnMapClickListener { latLng ->
            if (showAllTrackers) {
                val screen = map.projection.toScreenLocation(latLng)
                val point = PointF(screen.x, screen.y)
                val features = map.queryRenderedFeatures(point, ALL_TRACKS_POINTS_LAYER_ID)
                if (features.isEmpty()) {
                    clearMapSelection()
                    return@addOnMapClickListener false
                }
                val feature = if (features.size == 1) features[0] else {
                    features.minByOrNull { f: Feature ->
                        val geom = f.geometry()
                        if (geom !is Point) return@minByOrNull Float.MAX_VALUE
                        val fScreen = map.projection.toScreenLocation(LatLng(geom.latitude(), geom.longitude()))
                        val dx = fScreen.x - screen.x
                        val dy = fScreen.y - screen.y
                        sqrt(dx * dx + dy * dy)
                    } ?: features[0]
                }
                val selected = selectedMapTrackerFromFeature(feature) ?: return@addOnMapClickListener false
                if (selected.lastUpdateMs != null) lastKnownUpdateTimeMsByTrackerId[selected.id] = selected.lastUpdateMs
                selectedMapTracker = selected
                updateMapSelectionUi()
                return@addOnMapClickListener true
            }
            // Single-tracker mode: tap on tracker position shows info card
            val id = displayedTrackerId ?: run {
                clearMapSelection()
                return@addOnMapClickListener false
            }
            val screen = map.projection.toScreenLocation(latLng)
            val point = PointF(screen.x, screen.y)
            val positionFeatures = map.queryRenderedFeatures(point, TRACK_POSITION_LAYER_ID)
            if (positionFeatures.isNotEmpty()) {
                val feature = positionFeatures[0]
                val geom = feature.geometry()
                if (geom is Point) {
                    val sel = selectedMapTrackerFromDisplayedState(geom.latitude(), geom.longitude())
                    if (sel.lastUpdateMs != null) lastKnownUpdateTimeMsByTrackerId[sel.id] = sel.lastUpdateMs
                    selectedMapTracker = sel
                    updateMapSelectionUi()
                    return@addOnMapClickListener true
                }
            }
            // Default tracker uses location component (no symbol layer); treat tap near last point as tap on tracker
            if (trackPoints.isNotEmpty()) {
                val last = trackPoints.last()
                val lastScreen = map.projection.toScreenLocation(last)
                val dx = lastScreen.x - screen.x
                val dy = lastScreen.y - screen.y
                if (sqrt(dx * dx + dy * dy) <= TAP_NEAR_POINT_PX) {
                    val sel = selectedMapTrackerFromDisplayedState(last.latitude, last.longitude)
                    if (sel.lastUpdateMs != null) lastKnownUpdateTimeMsByTrackerId[sel.id] = sel.lastUpdateMs
                    selectedMapTracker = sel
                    updateMapSelectionUi()
                    return@addOnMapClickListener true
                }
            }
            clearMapSelection()
            false
        }
    }

    /** Build SelectedMapTracker from current single-tracker state (for tap-to-show info card). */
    private fun selectedMapTrackerFromDisplayedState(lat: Double, lon: Double): SelectedMapTracker {
        val hexColor = currentTrackerColor ?: defaultTrackerColorHex(requireContext()).let { if (it.startsWith("#")) it else "#$it" }
        val id = displayedTrackerId!!
        val lastUpdateMs = lastStreamedPointTimeMs ?: lastCachedUpdateTimeMs
            ?: (displayedTracker?.takeIf { it.id == id }?.let { trackerLastUpdateMs(it) })
            ?: lastKnownUpdateTimeMsByTrackerId[id]
            ?: TrackerRepository.getTrackerFromCache(id)?.let { trackerLastUpdateMs(it) }
        return SelectedMapTracker(
            id = id,
            name = displayedTrackerName ?: "",
            lat = lat,
            lon = lon,
            lastUpdateMs = lastUpdateMs,
            isOwner = displayedTrackerIsOwner,
            hexColor = hexColor
        )
    }

    private fun selectedMapTrackerFromFeature(feature: Feature): SelectedMapTracker? {
        if (feature.properties() == null) return null
        val id = getFeaturePropertyString(feature, "trackerId") ?: return null
        val name = getFeaturePropertyString(feature, "trackerName") ?: ""
        val lat = getFeaturePropertyDouble(feature, "lat") ?: return null
        val lon = getFeaturePropertyDouble(feature, "lon") ?: return null
        val lastUpdateMs = getFeaturePropertyDouble(feature, "lastUpdateMs")?.toLong()?.takeIf { it > 0L }
            ?: lastKnownUpdateTimeMsByTrackerId[id]
        val isOwner = getFeaturePropertyDouble(feature, "isOwner")?.let { it == 1.0 }
            ?: resolveTrackerIsOwner(null, id)
        val hexColor = getFeaturePropertyString(feature, "hexColor") ?: defaultTrackerColorHex(requireContext()).let { if (it.startsWith("#")) it else "#$it" }
        return SelectedMapTracker(id, name, lat, lon, lastUpdateMs, isOwner, hexColor)
    }

    private fun getFeaturePropertyString(feature: Feature, key: String): String? {
        val v = feature.properties()?.get(key) ?: return null
        return v.toString().trim('"')
    }

    private fun getFeaturePropertyDouble(feature: Feature, key: String): Double? {
        val v = feature.properties()?.get(key) ?: return null
        return v.toString().toDoubleOrNull()
    }

    /**
     * Some payloads may omit is_owner. Fallback to selected default tracker id so
     * map info-box list routing remains correct.
     */
    private fun resolveTrackerIsOwner(tracker: Tracker?, trackerId: String): Boolean {
        tracker?.is_owner?.let { return it }
        TrackerRepository.getTrackerFromCache(trackerId)?.is_owner?.let { return it }
        val defaultTrackerId = requireContext()
            .getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
            .getString("selected_tracker_id", "") ?: ""
        return trackerId == defaultTrackerId
    }

    private fun clearMapSelection() {
        if (selectedMapTracker == null) return
        selectedMapTracker = null
        lockTarget = null
        updateMapSelectionUi()
    }

    /** Rebuilds all-track point features with correct icon (white circle for selected) and updates the source. */
    private fun refreshAllTrackPointIcons() {
        val style = maplibreMap?.style ?: return
        val trackers = lastAllTrackers ?: return
        val coordsById = lastAllTrackersCoordsById ?: return
        val source = style.getSourceAs<GeoJsonSource>(ALL_TRACKS_POINTS_SOURCE_ID) ?: return
        val defaultColor = defaultTrackerColorHex(requireContext()).let { if (it.startsWith("#")) it else "#$it" }
        val pointFeatures = mutableListOf<Feature>()
        for (tracker in trackers) {
            val coords = coordsById[tracker.id] ?: tracker.geometry?.coordinates ?: emptyList()
            val hexColor = tracker.color?.let { if (it.startsWith("#")) it else "#$it" } ?: defaultColor
            val isSelected = tracker.id == selectedMapTracker?.id
            ensureArrowImageInStyle(style, hexColor, chevronOnly = !isSelected)
            val suffix = hexColor.replace("#", "")
            val imageId = if (isSelected) "track-direction-arrow-$suffix" else "track-direction-arrow-simple-$suffix"
            if (coords.isEmpty()) {
                tracker.last_point?.takeIf { it.size >= 2 }?.let { lp ->
                    val pt = LatLng(lp[1], lp[0])
                    val pointFeature = Feature.fromGeometry(Point.fromLngLat(lp[0], lp[1]))
                    pointFeature.addStringProperty("icon", imageId)
                    addTrackerPropertiesToPointFeature(pointFeature, tracker, pt.latitude, pt.longitude)
                    pointFeatures.add(pointFeature)
                }
            } else {
                val lastN = coords.takeLast(TrackUpdateHelper.MAX_POINTS)
                val points = lastN.map { c -> LatLng((c[1] as Number).toDouble(), (c[0] as Number).toDouble()) }
                val lastPoint = points.last()
                val rotation = if (points.size >= 2) getTrackDirectionDegrees(points) else 0f
                val lastUpdateMs = lastN.lastOrNull()?.get(2)?.let { t -> (t as? Number)?.toLong()?.let { n -> if (n < 1e12) n * 1000 else n } }
                val pointFeature = Feature.fromGeometry(Point.fromLngLat(lastPoint.longitude, lastPoint.latitude))
                pointFeature.addStringProperty("icon", imageId)
                pointFeature.addNumberProperty("rotate", rotation.toDouble())
                addTrackerPropertiesToPointFeature(pointFeature, tracker, lastPoint.latitude, lastPoint.longitude, lastUpdateMs)
                pointFeatures.add(pointFeature)
            }
        }
        source.setGeoJson(FeatureCollection.fromFeatures(pointFeatures))
    }

    private fun updateMapSelectionUi() {
        val sel = selectedMapTracker
        if (sel == null) {
            mapTrackerInfoCard.visibility = View.GONE
            if (lastSelectedTrackerIdForIcons != null) {
                lastSelectedTrackerIdForIcons = null
                refreshAllTrackPointIcons()
            }
            val shouldRecenterAfterClose = isFollowLockActive()
            refreshMapPaddingForCurrentMode(force = true, allowCameraMove = !shouldRecenterAfterClose)
            if (shouldRecenterAfterClose) {
                centerCameraOnTrackLocked(lockTarget!!)
            }
            return
        }
        val wasInfoCardVisible = mapTrackerInfoCard.visibility == View.VISIBLE
        val selectionIdChanged = lastSelectedTrackerIdForIcons != sel.id
        if (selectionIdChanged) {
            lastSelectedTrackerIdForIcons = sel.id
            refreshAllTrackPointIcons()
        }
        mapTrackerInfoCard.visibility = View.VISIBLE
        mapTrackerInfoName.text = sel.name.ifEmpty { getString(R.string.select_tracker) }
        mapTrackerInfoCoords.text = "%.4f, %.4f".format(java.util.Locale.US, sel.lat, sel.lon)
        mapTrackerInfoLastUpdated.text = sel.lastUpdateMs?.let { ts ->
            val diffMs = System.currentTimeMillis() - ts
            val diffSec = (diffMs / 1000).coerceAtLeast(0)
            val (n, unitResId) = when {
                diffSec < 60 -> {
                    val n = diffSec.toInt()
                    n to if (n == 1) R.string.map_updated_sec else R.string.map_updated_secs
                }
                diffSec < 3600 -> {
                    val n = (diffSec / 60).toInt()
                    n to if (n == 1) R.string.map_updated_min else R.string.map_updated_mins
                }
                diffSec < 86400 -> {
                    val n = (diffSec / 3600).toInt()
                    n to if (n == 1) R.string.map_updated_hr else R.string.map_updated_hrs
                }
                else -> {
                    val n = (diffSec / 86400).toInt()
                    n to if (n == 1) R.string.map_updated_day_short else R.string.map_updated_days_short
                }
            }
            getString(R.string.map_updated_ago, n, getString(unitResId))
        } ?: getString(R.string.waiting_for_data)
        val viewInListLabel = when {
            currentGroupForMap != null -> getString(R.string.view_in_group_members)
            sel.isOwner -> getString(R.string.view_in_trackers_list)
            else -> getString(R.string.view_in_shared_list)
        }
        mapTrackerInfoViewParams.contentDescription = getString(R.string.map_tracker_info_view_params_content_description)
        mapTrackerInfoViewInList.contentDescription = viewInListLabel
        updateFollowLockButton()
        val shouldRecenterOnInfoOpen = !wasInfoCardVisible || selectionIdChanged
        refreshMapPaddingForCurrentMode(force = true, allowCameraMove = false)
        if (shouldRecenterOnInfoOpen) {
            centerCameraOnTrackLocked(LatLng(sel.lat, sel.lon))
        }
    }

    private fun onMapTrackerInfoViewParams() {
        selectedMapTracker?.let { sel ->
            (activity as? MainActivity)?.showTrackerParamsFragment(
                sel.id,
                sel.name,
                lastUpdateMs = sel.lastUpdateMs,
                positionLat = sel.lat,
                positionLon = sel.lon
            )
        }
    }

    private fun onMapTrackerInfoZoomLock() {
        if (isFollowLockActive()) return
        val sel = selectedMapTracker ?: return
        val target = LatLng(sel.lat, sel.lon)
        lockTarget = target
        followLockEnabled = true
        followLockNeedsInitialZoom = true
        updateFollowLockButton()
        updateMapSelectionUi()
        centerCameraOnTrackLocked(target, forceZoomIn = true)
    }

    /** Switch map to single-tracker mode (name on top-left chip) for the selected tracker. */
    private fun onMapTrackerInfoFocus() {
        val sel = selectedMapTracker ?: return
        stopLiveTrackStreaming()
        clearMultiTrackContextState()
        activeCameraIntent = CameraIntent.SINGLE_TRACKER_FOCUS
        suppressStandaloneAutoZoomForTrackerFocus()
        showAllTrackers = false
        clearMapSelection()
        clearAllTrackSources()
        setAllTrackLayersVisibility(false)
        setAnnotationLayersVisibility(true)
        trackPoints.clear()
        trackTimestamps.clear()
        val prefs = requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
        val defaultTrackerId = prefs.getString("selected_tracker_id", "") ?: ""
        displayedTrackerId = sel.id
        displayedTrackerName = sel.name.ifEmpty { null }
        displayedTrackerIsOwner = sel.isOwner
        displayedGroupName = null
        mapViewContext = if (sel.id != defaultTrackerId) MapViewContext.SPECIFIC_TRACKER else MapViewContext.DEFAULT_TRACKER
        currentTrackerColor = sel.hexColor
        lastCachedUpdateTimeMs = sel.lastUpdateMs
        zoomToTrackAfterLoad = true
        followLockEnabled = false
        lockTarget = null
        updateFollowLockButton()
        updateTrackerLabel()
        startLiveTrackStreamingForDisplayedTracker()
        fetchFullGeometryAndApply(sel.id)
    }

    private fun onMapTrackerInfoViewInList() {
        val sel = selectedMapTracker ?: return
        when {
            currentGroupForMap != null ->
                (activity as? MainActivity)?.openGroupMembersAndScrollTo(currentGroupForMap!!, sel.id)
            sel.isOwner ->
                (activity as? MainActivity)?.openTrackersAndScrollTo(sel.id)
            else ->
                (activity as? MainActivity)?.openSharedAndScrollTo(sel.id)
        }
    }

    /** Add tracker id/name/coords/lastUpdate/owner/hexColor to a point feature for tap resolution. */
    private fun addTrackerPropertiesToPointFeature(
        feature: Feature,
        tracker: Tracker,
        lat: Double,
        lon: Double,
        lastUpdateMs: Long? = null
    ) {
        val ts = lastUpdateMs ?: trackerLastUpdateMs(tracker)
        feature.addStringProperty("trackerId", tracker.id)
        feature.addStringProperty("trackerName", tracker.name)
        feature.addNumberProperty("lat", lat)
        feature.addNumberProperty("lon", lon)
        feature.addNumberProperty("lastUpdateMs", (ts ?: 0L).toDouble())
        feature.addNumberProperty("isOwner", if (resolveTrackerIsOwner(tracker, tracker.id)) 1.0 else 0.0)
        val hexColor = tracker.color?.let { if (it.startsWith("#")) it else "#$it" } ?: defaultTrackerColorHex(requireContext()).let { if (it.startsWith("#")) it else "#$it" }
        feature.addStringProperty("hexColor", hexColor)
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
            displayedTracker = initial
            displayedTrackerId = initial.id
            displayedTrackerName = initial.name
            displayedTrackerIsOwner = initial.isOwner()
            displayedGroupName = null
            mapViewContext = if (initial.id != defaultTrackerId) MapViewContext.SPECIFIC_TRACKER else MapViewContext.DEFAULT_TRACKER
            lastCachedUpdateTimeMs = trackerLastUpdateMs(initial)
            currentTrackerColor = (initial.color ?: defaultTrackerColorHex(requireContext())).let { if (it.startsWith("#")) it else "#$it" }
            
            (initial.point_params?.lastOrNull()?.get("acc") as? Number)?.toFloat()?.takeIf { it > 0f }
                ?.let { lastStreamedAccuracyMeters = it }

            // Position camera using bbox or last_point; track line will be drawn by fetchFullGeometryAndApply
            val map = maplibreMap
            val allowTrackerCameraMoveInMyLocation =
                activeCameraIntent == CameraIntent.SINGLE_TRACKER_FOCUS ||
                    activeCameraIntent == CameraIntent.GROUP_MEMBER_FOCUS
            if (map != null && (!showMyLocationEnabled || allowTrackerCameraMoveInMyLocation)) {
                val bbox = initial.bbox
                if (bbox != null && bbox.size == 4) {
                    val bounds = LatLngBounds.Builder()
                        .include(LatLng(bbox[1], bbox[0]))
                        .include(LatLng(bbox[3], bbox[2]))
                        .build()
                    moveCameraToFitBoundsWithMinZoomClamp(map, bounds)
                    zoomToTrackAfterLoad = false
                } else if (initial.last_point != null && initial.last_point.size >= 2) {
                    applyUnifiedCameraMove(
                        map = map,
                        update = CameraUpdateFactory.newLatLngZoom(
                            LatLng(initial.last_point[1], initial.last_point[0]),
                            14.0
                        ),
                        paddingMode = CameraPaddingMode.OVERLAY_AWARE
                    )
                    zoomToTrackAfterLoad = false
                }
            }
            updateZoomToLatestButtonState()
        } else {
            // Fallback to default
            displayedTracker = null
            displayedTrackerId = defaultTrackerId
            val defaultName = prefs.getString("selected_tracker_name", "") ?: ""
            displayedTrackerName = defaultName.ifEmpty { null }
            displayedTrackerIsOwner = true
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
        clearMultiTrackContextState()
        activeCameraIntent = CameraIntent.SINGLE_TRACKER_FOCUS
        showAllTrackers = false
        currentGroupForMap = null
        clearMapSelection()
        clearAllTrackSources()
        setAllTrackLayersVisibility(false)
        setAnnotationLayersVisibility(true)
        trackPoints.clear()
        trackTimestamps.clear()
        displayedTracker = null
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
        displayedTrackerIsOwner = true
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
        // Prime list cache so first tap can show "Updated" from list data before geometry loads
        TrackerRepository.getTrackers(requireContext(), forceRefresh = false) { }
        // Zoom to default tracker extent on first load (e.g. app launch with Map tab) when we have no points yet
        if (trackerId == defaultTrackerId && trackPoints.isEmpty()) {
            zoomToTrackAfterLoad = true
        }
        fetchFullGeometryAndApply(trackerId)
    }

    private fun fetchFullGeometryAndApply(trackerId: String, forceReplace: Boolean = false) {
        if (geometryFetchInFlightTrackerId == trackerId) {
            return
        }
        geometryFetchInFlightTrackerId = trackerId
        geometryLoadingInProgress = true
        updateBottomRightSpinner(requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE).getString("selected_tracker_id", "") ?: "")
        TrackerRepository.getTrackerGeometry(requireContext(), trackerId) { tracker ->
            mainScope.launch {
                if (geometryFetchInFlightTrackerId == trackerId) {
                    geometryFetchInFlightTrackerId = null
                }
                geometryLoadingInProgress = false
                updateBottomRightSpinner(requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE).getString("selected_tracker_id", "") ?: "")
                val previousDisplayedTrackerId = displayedTrackerId
                val previousDisplayedTrackerIsOwner = displayedTrackerIsOwner
                displayedTracker = tracker
                displayedTrackerId = trackerId
                displayedTrackerName = tracker?.name
                displayedTrackerIsOwner = tracker?.is_owner ?: if (previousDisplayedTrackerId == trackerId) {
                    previousDisplayedTrackerIsOwner
                } else {
                    resolveTrackerIsOwner(tracker, trackerId)
                }
                lastCachedUpdateTimeMs = trackerLastUpdateMs(tracker)
                val defaultId = requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
                    .getString("selected_tracker_id", "") ?: ""
                displayedGroupName = null
                mapViewContext = if (trackerId != defaultId) MapViewContext.SPECIFIC_TRACKER else MapViewContext.DEFAULT_TRACKER
                if (tracker != null) {
                    val resolvedColor = (tracker.color ?: defaultTrackerColorHex(requireContext())).let { if (it.startsWith("#")) it else "#$it" }
                    currentTrackerColor = resolvedColor
                    maplibreMap?.style?.let { ensureArrowImageInStyle(it, resolvedColor, chevronOnly = false) }
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
                    val allowTrackerCameraMoveInMyLocation =
                        activeCameraIntent == CameraIntent.SINGLE_TRACKER_FOCUS ||
                            activeCameraIntent == CameraIntent.GROUP_MEMBER_FOCUS
                    if (map != null && trackPoints.isNotEmpty() &&
                        (!showMyLocationEnabled || allowTrackerCameraMoveInMyLocation)
                    ) {
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
                    if (!showMyLocationEnabled && isFollowLockActive()) {
                        lockTarget?.let { centerCameraOnTrackLocked(it, forceZoomIn = true) }
                    }
                    // In single tracker mode, lock on the latest point when the track first loads
                    if (!showAllTrackers && trackPoints.isNotEmpty() && !showMyLocationEnabled) {
                        followLockEnabled = true
                        followLockNeedsInitialZoom = true
                        lockTarget = trackPoints.lastOrNull()
                        lockTarget?.let { centerCameraOnTrackLocked(it, forceZoomIn = true) }
                        updateFollowLockButton()
                    }
                }
                restoreOnlyNoZoom = false
                updateZoomToLatestButtonState()
                updateTrackerLabel()
                startLiveTrackStreamingForDisplayedTracker()
            }
        }
    }

    /** Start live streaming for a specific displayed tracker when it's not the default local tracker. */
    private fun startLiveTrackStreamingForDisplayedTracker() {
        val id = displayedTrackerId ?: return
        // Stream whenever map is in explicit single-tracker context.
        // Do not depend on selected_tracker_id being configured.
        if (mapViewContext != MapViewContext.SPECIFIC_TRACKER) {
            stopLiveTrackStreaming()
            return
        }
        startLiveTrackStreamingForTrackerSet(setOf(id), displayedTrackerName)
    }

    /** Start live streaming for a set of trackers (group/all-trackers context). */
    private fun startLiveTrackStreamingForTrackerSet(trackerIds: Set<String>, trackerName: String? = null) {
        val cleanedIds = trackerIds.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
        if (cleanedIds.isEmpty()) {
            stopLiveTrackStreaming()
            return
        }
        activeStreamedTrackerIds = cleanedIds
        val intent = Intent(requireContext(), LiveTrackStreamingService::class.java).apply {
            action = LiveTrackStreamingService.ACTION_START
            putStringArrayListExtra(LiveTrackStreamingService.EXTRA_TRACKER_IDS, ArrayList(cleanedIds))
            if (cleanedIds.size == 1) {
                putExtra(LiveTrackStreamingService.EXTRA_TRACKER_ID, cleanedIds.first())
            }
            putExtra(LiveTrackStreamingService.EXTRA_TRACKER_NAME, trackerName)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun stopLiveTrackStreaming() {
        activeStreamedTrackerIds = emptySet()
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
        
        // Preload in fetch path and re-check here for safety.
        ensureArrowImageInStyle(style, hexColor, chevronOnly = false)
        if (style.getImage(imageId) == null) {
            symbolIconId = "track-direction-arrow" // Fallback name
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

        if (showingDefault && !showMyLocationEnabled) {
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

        if (!showMyLocationEnabled) {
            LocationComponentHelper.setEnabled(map, false)
        }

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
        private const val KEY_SHOW_MY_LOCATION = "show_my_location"
        /** Duration (ms) for animating the camera when follow lock is on and the track moves. */
        private const val FOLLOW_LOCK_ANIMATION_MS = 300
        private const val STANDALONE_FIX_FRESHNESS_MS = 20_000L
        /** No padding so the lock target is centered in the viewport. */
        private val FOLLOW_LOCK_PADDING = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        /** Target zoom when enabling follow lock from a zoomed-out state. */
        private const val FOLLOW_LOCK_TARGET_ZOOM = 16.0
        private const val FOLLOW_LOCK_TARGET_ZOOM_EPSILON = 0.05
        /** Zoom used when opening a specific tracker from group members onto the group map. */
        private const val TRACKER_CARD_FOCUS_ZOOM = 14.0
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
        /** Approximate height (dp) of the tracker info card when visible for padding. */
        private const val MAP_TRACKER_INFO_CARD_HEIGHT_DP = 200
        /** Coalesce bursts of multi-tracker streamed points before full layer redraw. */
        private const val MULTI_TRACK_RENDER_DEBOUNCE_MS = 120L

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
        /** Max distance (px) from tracker position to count as tap-on-tracker in single-tracker mode (default tracker uses location dot). */
        private const val TAP_NEAR_POINT_PX = 80f
    }

    private enum class MapViewContext {
        DEFAULT_TRACKER,
        SPECIFIC_TRACKER,
        GROUP
    }

    private enum class CameraPaddingMode {
        CENTERED,
        OVERLAY_AWARE
    }

    private enum class CameraIntent {
        NONE,
        BOUNDS_FIT,
        GROUP_MEMBER_FOCUS,
        SINGLE_TRACKER_FOCUS,
        FOLLOW_LOCK
    }
}
