package com.geovault.tracker.fragments.map

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
import android.widget.ImageView
import android.widget.TextView
import com.geovault.common.LoadingSpinner
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.core.os.bundleOf
import com.geovault.common.map.GeoVaultMapFragment
import com.geovault.common.map.LocationComponentHelper
import com.geovault.common.map.MapLibreManager
import com.geovault.tracker.defaultTrackerColorHex
import com.geovault.tracker.LiveTrackStreamingService
import com.geovault.tracker.MainActivity
import com.geovault.tracker.Group
import com.geovault.tracker.R
import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerRepository
import com.geovault.tracker.TrackingService
import com.geovault.tracker.TrackUpdateHelper
import com.geovault.tracker.fragments.TrackersListFragment
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
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.TransitionOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import android.graphics.PointF
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

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
    private lateinit var trackerLabelIcon: ImageView
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
    private lateinit var liveActiveFitButton: MaterialCardView
    private lateinit var liveActiveFitButtonIcon: ImageView
    private lateinit var mapTrackerInfoCard: View
    private lateinit var mapTrackerInfoName: TextView
    private lateinit var mapTrackerInfoCoords: TextView
    private lateinit var mapTrackerInfoLastUpdated: TextView
    private lateinit var mapTrackerInfoViewParams: MaterialButton
    private lateinit var mapTrackerInfoViewInList: MaterialButton
    private lateinit var mapTrackerInfoZoomLock: ImageView
    private lateinit var mapTrackerInfoFocus: View
    private lateinit var showMyLocationButton: View
    private lateinit var showMyLocationButtonIcon: ImageView
    private lateinit var showMyLocationButtonLoading: LoadingSpinner

    /** When true, map shows all trackers; when false, a single displayed tracker. */
    private var showAllTrackers = false
    /** Group-only mode: fit to trackers with recent live updates. */
    private var liveActiveFitEnabled = false

    /** True while fetchFullGeometryAndApply is in progress; used so bottom-right spinner stays visible if streaming starts. */
    private var geometryLoadingInProgress = false
    /** Tracker id currently loading via geometry API; used to coalesce duplicate lifecycle fetches. */
    private var geometryFetchInFlightTrackerId: String? = null
    /** Tracker id currently loading via coordinates API; used to avoid duplicate warm-start tail requests. */
    private var coordinatesFetchInFlightTrackerId: String? = null

    /** Last streamed point timestamp (ms) for the currently displayed single tracker. */
    private var lastStreamedPointTimeMs: Long? = null
    /** Last streamed point accuracy (m) for the currently displayed single tracker. */
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
    /** Id of the tracker currently shown on the map. */
    private var displayedTrackerId: String? = null
    /** Name of the tracker currently shown on the map; used for the label in the upper left. */
    private var displayedTrackerName: String? = null
    /** Whether the displayed tracker is owned by the user; used for info card "View in list" label. */
    private var displayedTrackerIsOwner: Boolean = true
    /** Group currently shown on map, when in group context. */
    private var displayedGroupName: String? = null
    /** Explicit map UI context used for chip/button state. */
    private var mapViewContext: MapViewContext = MapViewContext.SINGLE_TRACKER
    /** Dirty flag for debounced track line updates. */
    private var trackLineDirty = false
    private var styleReloadListener: MapView.OnDidFinishLoadingStyleListener? = null
    /** When in group map context, the group being displayed (for "View in list" routing). */
    private var currentGroupForMap: Group? = null
    /** Pending group to apply when map becomes ready (set when refreshMapForGroup is called before maplibreMap is ready). */
    private var pendingGroupForMap: Group? = null
    private var pendingGroupZoomToTrackerId: String? = null
    /** Pending all-trackers intent to replay once map/style is ready. */
    private var pendingShowAllTrackers = false
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
    /** Monotonic token used to reject stale single-tracker async callbacks. */
    private var trackerRequestEpoch: Long = 0L
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
    private val liveStreamCoordinator = MapLiveStreamCoordinator(mainScope)

    private val liveTrackPointReceiver by lazy {
        MapBroadcastHandlers.createLiveTrackPointReceiver { trackId, lat, lon, tsMs, accuracyMeters ->
            if (accuracyMeters != null) lastStreamedAccuracyMeters = accuracyMeters
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
        MapLiveStreamPointHandler.applyLiveStreamPoint(trackId, lat, lon, timestampMs, buildLiveStreamPointCallbacks())
    }

    private fun buildLiveStreamPointCallbacks(): MapLiveStreamPointCallbacks {
        return MapLiveStreamPointCallbacks(
            getShowAllTrackers = { showAllTrackers },
            getMapViewContext = { mapViewContext },
            getActiveStreamedTrackerIds = { activeStreamedTrackerIds },
            getLastAllTrackers = { lastAllTrackers },
            getTrackerBaseCoordsForMultiContext = { tracker, trackId ->
                MapStreamingDataHelper.getTrackerBaseCoordsForMultiContext(
                    tracker, trackId, multiTrackCoordsCache, lastAllTrackersCoordsById
                ) { t -> MapStreamingDataHelper.seedCoordsFromLastPoint(t, ::trackerLastUpdateMs) }
            },
            setMultiTrackCoordsCache = { id, coords -> multiTrackCoordsCache[id] = coords },
            setLastKnownUpdateTimeMsByTrackerId = { id, ms -> lastKnownUpdateTimeMsByTrackerId[id] = ms },
            getSelectedMapTracker = { selectedMapTracker },
            onUpdateSelectedMapTracker = { _, lat, lon, lastUpdateMs ->
                selectedMapTracker = selectedMapTracker?.copy(lat = lat, lon = lon, lastUpdateMs = lastUpdateMs)
            },
            onRecenterFollowLock = { target ->
                lockTarget = target
                centerCameraOnTrackLocked(target)
            },
            getShowMyLocationEnabled = { showMyLocationEnabled },
            getIsFollowLockActive = { isFollowLockActive() },
            scheduleDebouncedMultiTrackRender = { scheduleDebouncedMultiTrackRender() },
            updateMapSelectionUi = { updateMapSelectionUi() },
            getDisplayedTrackerId = { displayedTrackerId },
            getIsAdded = { isAdded },
            setLastStreamedPointTimeMs = { lastStreamedPointTimeMs = it },
            updateStreamingUi = { updateStreamingUi() },
            addTrackPoint = { latLng, ts -> TrackUpdateHelper.updateTrack(trackPoints, trackTimestamps, latLng, ts) },
            scheduleTrackLineUpdate = { scheduleTrackLineUpdate() },
            updateZoomToLatestButtonState = { updateZoomToLatestButtonState() },
            scheduleDebouncedSingleLiveFit = { scheduleDebouncedSingleLiveFit() },
            getLiveActiveFitEnabled = { liveActiveFitEnabled }
        )
    }

    private fun scheduleDebouncedMultiTrackRender() {
        liveStreamCoordinator.scheduleMultiTrackRender(MapConstants.MULTI_TRACK_RENDER_DEBOUNCE_MS) stream@{
            if (!isAdded || !(showAllTrackers || mapViewContext == MapViewContext.GROUP)) return@stream
            val trackers = lastAllTrackers ?: return@stream
            val map = maplibreMap ?: return@stream
            val style = map.style ?: return@stream
            val useLiveActiveFit = liveActiveFitEnabled && isLiveActiveFitAvailable()
            applyAllTrackersToMap(
                trackers,
                multiTrackCoordsCache.mapValues { it.value.toList() },
                map,
                style,
                fitBounds = useLiveActiveFit,
                liveActiveOnlyFit = useLiveActiveFit
            )
        }
    }

    private fun scheduleDebouncedSingleLiveFit() {
        liveStreamCoordinator.scheduleSingleLiveFit(MapConstants.SINGLE_LIVE_FIT_DEBOUNCE_MS) {
            applySingleTrackerLiveFitNow()
        }
    }

    private fun clearMultiTrackContextState(clearPendingGroupIntent: Boolean = true) {
        liveStreamCoordinator.clearAll()
        multiTrackCoordsCache.clear()
        lastAllTrackers = null
        lastAllTrackersCoordsById = null
        activeCameraIntent = CameraIntent.NONE
        if (clearPendingGroupIntent) {
            pendingGroupForMap = null
            pendingGroupZoomToTrackerId = null
            pendingShowAllTrackers = false
        }
    }

    private fun bumpTrackerRequestEpoch() {
        trackerRequestEpoch++
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
        trackerLabelIcon = view.findViewById(R.id.trackerLabelIcon)
        trackerNameLabel = view.findViewById(R.id.trackerNameLabel)
        resetToTrackerButton = view.findViewById(R.id.resetToTrackerButton)
        trackerLabelIcon.setImageTintList(ContextCompat.getColorStateList(requireContext(), R.color.content_on_primary))
        mapToggle = view.findViewById(R.id.mapToggle)
        zoomToLatestButton = view.findViewById(R.id.zoomToLatestButton)
        zoomToLatestButtonIcon = view.findViewById(R.id.zoomToLatestButtonIcon)
        zoomInButton = view.findViewById(R.id.zoomInButton)
        zoomOutButton = view.findViewById(R.id.zoomOutButton)
        geometryLoadingSpinner = view.findViewById(R.id.geometryLoadingSpinner)
        lastUpdatedLabel = view.findViewById(R.id.lastUpdatedLabel)
        showAllTrackersButton = view.findViewById(R.id.showAllTrackersButton)
        liveActiveFitButton = view.findViewById(R.id.liveActiveFitButton)
        liveActiveFitButtonIcon = view.findViewById(R.id.liveActiveFitButtonIcon)
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
        mapTrackerInfoFocus = view.findViewById(R.id.mapTrackerInfoFocus)
        mapTrackerInfoFocus.setOnClickListener { onMapTrackerInfoFocus() }
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
                map.setMinZoomPreference(MapConstants.MIN_ZOOM)
                mapManager = mapFragment?.mapManager
                val mgr = mapManager ?: return
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
                MapStyleSetup.configure(
                    context = requireContext(),
                    map = map,
                    style = style,
                    ids = MapStyleIds(
                        trackSourceId = MapConstants.TRACK_SOURCE_ID,
                        trackOuterOutlineLayerId = MapConstants.TRACK_OUTER_OUTLINE_LAYER_ID,
                        trackOutlineLayerId = MapConstants.TRACK_OUTLINE_LAYER_ID,
                        trackFillLayerId = MapConstants.TRACK_FILL_LAYER_ID,
                        trackPositionSourceId = MapConstants.TRACK_POSITION_SOURCE_ID,
                        trackPositionAccuracySourceId = MapConstants.TRACK_POSITION_ACCURACY_SOURCE_ID,
                        trackPositionLayerId = MapConstants.TRACK_POSITION_LAYER_ID,
                        trackPositionAccuracyLayerId = MapConstants.TRACK_POSITION_ACCURACY_LAYER_ID,
                        allTracksSourceId = MapConstants.ALL_TRACKS_SOURCE_ID,
                        allTracksPointsSourceId = MapConstants.ALL_TRACKS_POINTS_SOURCE_ID,
                        allTracksOuterOutlineLayerId = MapConstants.ALL_TRACKS_OUTER_OUTLINE_LAYER_ID,
                        allTracksOutlineLayerId = MapConstants.ALL_TRACKS_OUTLINE_LAYER_ID,
                        allTracksFillLayerId = MapConstants.ALL_TRACKS_FILL_LAYER_ID,
                        allTracksPointsLayerId = MapConstants.ALL_TRACKS_POINTS_LAYER_ID
                    )
                )
                map.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                    override fun onMoveBegin(detector: org.maplibre.android.gestures.MoveGestureDetector) {
                        // User panned the map manually; clear sticky camera intent.
                        activeCameraIntent = CameraIntent.NONE
                        preserveCenteredAllTrackersFit = false
                        disableLiveActiveFitForManualCameraInteraction()
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
                map.addOnCameraMoveStartedListener { reason ->
                    if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                        disableLiveActiveFitForManualCameraInteraction()
                    }
                }
                setupMapTapListener(map)
                if (styleReloadListener == null) {
                    styleReloadListener = MapView.OnDidFinishLoadingStyleListener {
                        if (!isAdded) return@OnDidFinishLoadingStyleListener
                        reapplyLocationMarkerStyleForCurrentMode()
                    }
                    mapFragment?.mapViewOrNull?.addOnDidFinishLoadingStyleListener(styleReloadListener!!)
                }
                mapReady = true
                mapLoadingOverlay.visibility = View.GONE
                refreshMapPaddingForCurrentMode(force = true)
                updateTrackLine()
                if (showMyLocationEnabled && pendingAutoZoomToStandaloneFix) {
                    val loc = lastStandaloneLocation
                    val zoomApplied = loc != null && zoomToStandaloneLocation(loc, forceZoomIn = true, animate = true)
                    if (MapStandaloneLocationController.shouldConsumePendingAutoZoom(
                            pendingAutoZoom = pendingAutoZoomToStandaloneFix,
                            trackerFocusIntentActive = isTrackerFocusIntentActive(),
                            suppressStandaloneAutoZoom = suppressStandaloneAutoZoom,
                            zoomApplied = zoomApplied
                        )
                    ) {
                        pendingAutoZoomToStandaloneFix = false
                    }
                }
                if (zoomToTrackAfterLoad && trackPoints.isNotEmpty()) {
                    zoomToLatestTrackPoint(map)
                    zoomToTrackAfterLoad = false
                }
                val (deferredGroup, deferredZoom) = (activity as? MainActivity)?.getAndClearInitialGroupAndZoomForMap() ?: Pair(null, null)
                if (deferredGroup != null) {
                    refreshMapForGroup(deferredGroup, deferredZoom)
                    return
                }
                val pendingGroup = pendingGroupForMap
                val pendingZoom = pendingGroupZoomToTrackerId
                if (pendingGroup != null) {
                    refreshMapForGroup(pendingGroup, pendingZoom)
                    pendingGroupForMap = null
                    pendingGroupZoomToTrackerId = null
                    return
                }
                if (pendingShowAllTrackers) {
                    pendingShowAllTrackers = false
                    loadAllTrackersAndApply()
                    return
                }
                if (mapViewContext == MapViewContext.GROUP && currentGroupForMap != null) {
                    refreshMapForGroup(currentGroupForMap, null)
                    return
                }
                if (showAllTrackers) {
                    if (!restoreAllTrackersFromCacheIfAvailable(map, style)) {
                        loadAllTrackersAndApply()
                    }
                    return
                }
                // Basemap switch should keep currently rendered single-tracker tail visible.
                // If we already have points, avoid kicking the full history refetch path.
                if (trackPoints.isNotEmpty() && !displayedTrackerId.isNullOrEmpty()) {
                    startLiveTrackStreamingForDisplayedTracker()
                    updateZoomToLatestButtonState()
                    updateTrackerLabel()
                    return
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
            MapSingleTrackFetch.cancelGeometry()
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
            val deletedId = bundle?.getString(TrackersListFragment.KEY_DELETED_TRACKER_ID)
            if (deletedId != null) {
                if (deletedId == displayedTrackerId) {
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
            scheduleReapplyLocationMarkerStyleAfterSourceChange(map)
        }

        zoomToLatestButton.setOnClickListener {
            // Lock button is one-way: when already locked, tapping does nothing.
            // Unlocking is handled only by map interaction (pan/zoom gestures).
            if (isFollowLockActive()) return@setOnClickListener

            val target = lockTarget ?: trackPoints.lastOrNull()
            if (target != null) {
                disableLiveActiveFitForFollowLock()
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
            disableLiveActiveFitForManualCameraInteraction()
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
            disableLiveActiveFitForManualCameraInteraction()
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
        liveActiveFitButton.setOnClickListener { onLiveActiveFitButtonClick() }
        view.post { if (isAdded) refreshMapPaddingForCurrentMode(force = true) }
    }

    override fun onResume() {
        super.onResume()
        view?.let { root ->
            root.overScrollMode = View.OVER_SCROLL_NEVER
            root.findViewById<View>(R.id.mapContainer)?.overScrollMode = View.OVER_SCROLL_NEVER
        }
        view?.keepScreenOn = true
        updateTrackerLabel()
        refreshMapPaddingForCurrentMode(force = true)

        if (TrackingService.isRunning) {
            // Local tracking owns live updates; do not run websocket streaming in tracking mode.
            stopLiveTrackStreaming()
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
                // Map/style can be recreated while this mode stays enabled; re-assert GPS marker style.
                applyStandaloneLocationStyle()
                waitingForStandaloneFix = true
                pendingAutoZoomToStandaloneFix = true
            }
            startStandaloneLocationUpdates()
        }

        // Drain first, then register receiver. This avoids duplicate processing windows where
        // one point can be consumed both via broadcast and buffer replay.
        if (TrackingService.isRunning) {
            val trackedBuffered = TrackingService.drainBufferedTrackPoints()
            if (trackedBuffered.isNotEmpty()) {
                for (p in trackedBuffered) {
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
            // Tracking mode should not replay stale websocket points.
            LiveTrackStreamingService.drainBufferedPoints()
        } else {
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
        }

        ContextCompat.registerReceiver(
            requireContext(),
            liveTrackPointReceiver,
            IntentFilter(LiveTrackStreamingService.BROADCAST_TRACK_POINT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        if (mapReady) {
            if (mapViewContext == MapViewContext.GROUP || showAllTrackers) {
                if (activeStreamedTrackerIds.isNotEmpty()) {
                    startLiveTrackStreamingForTrackerSet(activeStreamedTrackerIds)
                } else if (mapViewContext == MapViewContext.GROUP && currentGroupForMap != null) {
                    val group = currentGroupForMap!!
                    val trackIds = group.track_ids?.toSet() ?: emptySet()
                    if (trackIds.isNotEmpty()) {
                        TrackerRepository.getTrackers(requireContext(), forceRefresh = false) { list ->
                            if (!isAdded) return@getTrackers
                            val allTrackers = list ?: emptyList()
                            val trackers = allTrackers.filter { it.id in trackIds }
                            if (trackers.isNotEmpty()) {
                                startLiveTrackStreamingForTrackerSet(trackers.map { it.id }.toSet())
                            }
                            updateTrackerLabel()
                        }
                        return
                    }
                }
                updateTrackerLabel()
                return
            }
            val selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(requireContext())
            val activeTrackerId = if (TrackingService.isRunning) {
                selectedTrackerId
            } else {
                displayedTrackerId ?: selectedTrackerId
            }
            val pendingInitialTracker = (activity as? MainActivity)?.initialTrackForMap != null
            if (activeTrackerId.isEmpty() && !pendingInitialTracker) {
                // No selected tracker and no explicit tracker target: clear stale map state.
                trackPoints.clear()
                displayedTracker = null
                displayedTrackerId = null
                displayedTrackerName = null
                stopLiveTrackStreaming()
                updateTrackLine()
                updateZoomToLatestButtonState()
                updateTrackerLabel()
            } else if (TrackingService.isRunning && activeTrackerId.isNotEmpty()) {
                // Tracking may continue while app/map is backgrounded; always refresh tail + full
                // geometry on resume to catch up even if in-memory broadcast buffer was capped/reset.
                if (displayedTrackerId != activeTrackerId) {
                    displayedTrackerId = activeTrackerId
                    mapViewContext = MapViewContext.SINGLE_TRACKER
                }
                seedTrackFromCacheOrTail(activeTrackerId, allowCoordinatesNetwork = true)
                fetchFullGeometryAndApply(activeTrackerId, forceReplace = false)
            } else if (trackPoints.isEmpty() && activeTrackerId.isNotEmpty()) {
                // Rehydrate from cache/history regardless of whether this is selected or directly opened.
                if (displayedTrackerId == null) {
                    displayedTrackerId = activeTrackerId
                    mapViewContext = MapViewContext.SINGLE_TRACKER
                }
                restoreOnlyNoZoom = true
                seedTrackFromCacheOrTail(activeTrackerId, allowCoordinatesNetwork = true)
                fetchFullGeometryAndApply(activeTrackerId, forceReplace = false)
            } else {
                // Re-start streaming when returning to Map (e.g. after closing Params overlay).
                startLiveTrackStreamingForDisplayedTracker()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        view?.keepScreenOn = false
        stopStandaloneLocationUpdates(clearGpsFix = true)
        try {
            requireContext().unregisterReceiver(liveTrackPointReceiver)
        } catch (e: IllegalArgumentException) { }
    }

    override fun onDestroyView() {
        styleReloadListener?.let { listener ->
            mapFragment?.mapViewOrNull?.removeOnDidFinishLoadingStyleListener(listener)
        }
        styleReloadListener = null
        mapFragment?.setCallback(null)
        mapFragment = null
        mapManager = null
        maplibreMap = null
        mapReady = false
        geometryFetchInFlightTrackerId = null
        coordinatesFetchInFlightTrackerId = null
        // Preserve deferred group handoff across view teardown so onMapReady can still apply it.
        clearMultiTrackContextState(clearPendingGroupIntent = false)
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
        mapFragment?.mapViewOrNull?.onLowMemory()
    }

    private fun isFollowLockActive(): Boolean = followLockEnabled && lockTarget != null

    private fun zoomButtonsPaddingMode(): CameraPaddingMode =
        MapZoomOrchestrator.zoomButtonsPaddingMode(activeCameraIntent, isFollowLockActive())

    private fun updateFollowLockButton() {
        val (iconResId, contentDescResId) = MapCameraController.followLockButtonContent(isFollowLockActive())
        zoomToLatestButtonIcon.setImageResource(iconResId)
        zoomToLatestButtonIcon.contentDescription = getString(contentDescResId)
        mapTrackerInfoZoomLock.setImageResource(iconResId)
    }

    private fun updateZoomToLatestButtonState() {
        val hasTrack = !showAllTrackers && trackPoints.isNotEmpty()
        zoomToLatestButton.visibility = if (hasTrack) View.VISIBLE else View.GONE
        updateRightStackMargins()
    }

    /** Right-stack buttons in top-to-bottom order. When a button is GONE, no space is reserved; visible buttons are re-stacked from top. */
    private fun updateRightStackMargins() {
        MapPaddingRefresher.updateRightStackMargins(
            resources = resources,
            ordered = listOf(
                zoomToLatestButton,
                showMyLocationButton,
                mapToggle,
                zoomInButton,
                zoomOutButton,
                showAllTrackersButton,
                liveActiveFitButton
            )
        )
        // Stack visibility/layout updates should not move camera; only update manager/default padding.
        refreshMapPaddingForCurrentMode(force = true, allowCameraMove = false)
    }

    private fun updateShowMyLocationButtonVisibility() {
        val state = MapStandaloneLocationController.myLocationButtonState(
            trackingRunning = TrackingService.isRunning,
            showMyLocationEnabled = showMyLocationEnabled,
            waitingForFix = waitingForStandaloneFix,
            context = requireContext()
        )
        showMyLocationButton.visibility = state.visibility
        if (state.visibility == View.VISIBLE) {
            if (state.showLoading) showMyLocationButtonLoading.show() else showMyLocationButtonLoading.hide()
            showMyLocationButtonIcon.visibility = if (state.showLoading) View.GONE else View.VISIBLE
            showMyLocationButtonIcon.setImageResource(state.iconResId)
            showMyLocationButtonIcon.contentDescription = getString(state.contentDescriptionResId)
        } else {
            showMyLocationButtonLoading.hide()
            showMyLocationButtonIcon.visibility = View.VISIBLE
        }
        updateLiveActiveFitButtonUi()
        updateRightStackMargins()
    }

    private fun zoomToStandaloneLocation(
        location: Location,
        forceZoomIn: Boolean = true,
        animate: Boolean = true
    ): Boolean {
        return MapZoomOrchestrator.zoomToStandaloneLocation(
            map = maplibreMap,
            location = location,
            forceZoomIn = forceZoomIn,
            animate = animate,
            followLockTargetZoom = MapConstants.FOLLOW_LOCK_TARGET_ZOOM
        ) { update, paddingMode, intent, moveAnimate, durationMs, callback ->
            maplibreMap?.let { map ->
                applyUnifiedCameraMove(
                    map = map,
                    update = update,
                    paddingMode = paddingMode,
                    intent = intent,
                    animate = moveAnimate,
                    durationMs = durationMs,
                    callback = callback
                )
            }
        }
    }

    private fun isFreshStandaloneFix(location: Location): Boolean {
        val now = System.currentTimeMillis()
        return location.time > 0L && (now - location.time) <= MapConstants.STANDALONE_FIX_FRESHNESS_MS
    }

    @SuppressLint("MissingPermission")
    private fun onShowMyLocationClick() {
        val activity = activity as? MainActivity ?: return
        if (!activity.hasLocationPermission()) {
            activity.requestLocationPermission()
            return
        }
        // GPS recenter is a user camera action; disable live-fit one-way lock state.
        disableLiveActiveFitForManualCameraInteraction()
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
            // Re-assert standalone marker style before recentering.
            // This avoids stale tracker-chevron style when map/style was recreated.
            applyStandaloneLocationStyle()
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
        MapStandaloneLocationController.applyStandaloneStyle(map, requireContext())
    }

    /** Restore tracker arrow/circle style when leaving standalone mode. */
    private fun restoreTrackerLocationStyle() {
        val map = maplibreMap ?: return
        MapStandaloneLocationController.applyTrackerStyle(map, requireContext())
        // Restore normal position display (track or hidden)
        updateTrackLine()
    }

    private fun reapplyLocationMarkerStyleForCurrentMode() {
        if (showMyLocationEnabled) {
            applyStandaloneLocationStyle()
        } else {
            restoreTrackerLocationStyle()
        }
    }

    private fun scheduleReapplyLocationMarkerStyleAfterSourceChange(map: MapLibreMap) {
        // Source/style switches may apply asynchronously. Re-assert now and once after a short delay.
        map.getStyle {
            if (!isAdded) return@getStyle
            reapplyLocationMarkerStyleForCurrentMode()
        }
        view?.postDelayed({
            if (!isAdded) return@postDelayed
            map.getStyle {
                if (!isAdded) return@getStyle
                reapplyLocationMarkerStyleForCurrentMode()
            }
        }, 250L)
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
                    if (MapStandaloneLocationController.shouldConsumePendingAutoZoom(
                            pendingAutoZoom = pendingAutoZoomToStandaloneFix,
                            trackerFocusIntentActive = isTrackerFocusIntentActive(),
                            suppressStandaloneAutoZoom = suppressStandaloneAutoZoom,
                            zoomApplied = zoomToStandaloneLocation(location, forceZoomIn = true, animate = true)
                        )
                    ) {
                        pendingAutoZoomToStandaloneFix = false
                    }
                }
                if (liveActiveFitEnabled) {
                    scheduleDebouncedSingleLiveFit()
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
                        if (MapStandaloneLocationController.shouldConsumePendingAutoZoom(
                                pendingAutoZoom = pendingAutoZoomToStandaloneFix,
                                trackerFocusIntentActive = isTrackerFocusIntentActive(),
                                suppressStandaloneAutoZoom = suppressStandaloneAutoZoom,
                                zoomApplied = zoomToStandaloneLocation(location, forceZoomIn = true, animate = true)
                            )
                        ) {
                            pendingAutoZoomToStandaloneFix = false
                        }
                    }
                }
                if (liveActiveFitEnabled) {
                    scheduleDebouncedSingleLiveFit()
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
        val trackingRunning = TrackingService.isRunning
        val selectedTrackerName = SelectedTrackerPrefs.selectedTrackerName(requireContext())
        if (!isLiveActiveFitAvailable() && liveActiveFitEnabled) {
            liveActiveFitEnabled = false
        }
        val state = MapTrackerLabelController.computeLabelState(
            mapViewContext = mapViewContext,
            showAllTrackers = showAllTrackers,
            displayedTrackerId = displayedTrackerId,
            displayedTrackerName = displayedTrackerName,
            displayedGroupName = displayedGroupName,
            selectedTrackerName = selectedTrackerName,
            trackingRunning = trackingRunning,
            context = requireContext()
        )
        MapTrackerHeaderUiHelper.applyLabelState(
            state = state,
            resources = resources,
            trackerLabelCard = trackerLabelCard,
            trackerLabelIcon = trackerLabelIcon,
            trackerNameLabel = trackerNameLabel,
            lastUpdatedLabel = lastUpdatedLabel,
            resetToTrackerButton = resetToTrackerButton,
            showAllTrackersButton = showAllTrackersButton,
            showAllTrackers = showAllTrackers,
            getString = ::getString,
            onHideCardClearDisplayed = {
                displayedTracker = null
                displayedTrackerId = null
                displayedTrackerName = null
                displayedGroupName = null
                mapViewContext = MapViewContext.SINGLE_TRACKER
                lastCachedUpdateTimeMs = null
            },
            updateStreamingUi = ::updateStreamingUi
        )
        if (trackingRunning) {
            showAllTrackersButton.visibility = View.GONE
        }
        updateLiveActiveFitButtonUi()
        updateRightStackMargins()
    }

    /** Return true if we are currently viewing an active single-tracker stream. */
    fun isShowingStreamedTrack(): Boolean {
        if (!isAdded) return false
        return isStreaming()
    }

    /** True when a single tracker is currently active for live updates. */
    private fun isStreaming(): Boolean {
        if (TrackingService.isRunning) return false
        if (!LiveTrackStreamingService.isRunning) return false
        return if (showAllTrackers || mapViewContext == MapViewContext.GROUP) {
            activeStreamedTrackerIds.isNotEmpty()
        } else {
            !displayedTrackerId.isNullOrEmpty()
        }
    }

    private fun updateStreamingUi() {
        val streamingDisplayedTrackerId = if (isStreaming()) displayedTrackerId else null
        val state = MapTrackerLabelController.computeStreamingLabelState(
            streamingDisplayedTrackerId,
            lastStreamedPointTimeMs,
            lastCachedUpdateTimeMs,
            requireContext()
        )
        MapTrackerHeaderUiHelper.applyStreamingState(
            state = state,
            lastUpdatedLabel = lastUpdatedLabel,
            clearCachedStreamingState = {
                if (!MapTrackerLabelController.isStreaming(displayedTrackerId)) {
                    lastStreamedPointTimeMs = null
                    lastCachedUpdateTimeMs = null
                }
            },
            updateBottomRightSpinner = ::updateBottomRightSpinner
        )
    }

    /** Extract last update timestamp (ms) from tracker geometry, last_point, or updated_at; same convention as TrackersListFragment. */
    private fun trackerLastUpdateMs(tracker: Tracker?): Long? {
        if (tracker == null) return null
        val coord = tracker.geometry?.coordinates?.lastOrNull() ?: tracker.last_point
        if (coord != null) {
            MapCoordinateUtils.timestampFromCoordinateMs(coord)?.let { return it }
        }
        // Fallback to list/API updated_at so we never show "Waiting for data" when we have cached data
        val u = tracker.updated_at ?: return null
        return MapCoordinateUtils.normalizeTimestampToMs(u)
    }

    /** Show bottom-right spinner when loading geometry or when a live track is active. */
    private fun updateBottomRightSpinner() {
        val show = geometryLoadingInProgress || isStreaming()
        if (show) geometryLoadingSpinner.show() else geometryLoadingSpinner.hide()
    }

    /**
     * Keeps the map centered on the given point when follow lock is on.
     * When [forceZoomIn] is true, make sure we jump to follow zoom first.
     */
    private fun centerCameraOnTrackLocked(target: LatLng, forceZoomIn: Boolean = false) {
        MapZoomOrchestrator.centerCameraOnTrackLocked(
            map = maplibreMap,
            target = target,
            forceZoomIn = forceZoomIn,
            followLockNeedsInitialZoom = followLockNeedsInitialZoom,
            followLockTargetZoom = MapConstants.FOLLOW_LOCK_TARGET_ZOOM,
            followLockTargetZoomEpsilon = MapConstants.FOLLOW_LOCK_TARGET_ZOOM_EPSILON,
            isAdded = { isAdded },
            onFollowLockNeedsInitialZoomChanged = { followLockNeedsInitialZoom = it }
        ) { update, paddingMode, intent, animate, durationMs, callback ->
            maplibreMap?.let { map ->
                applyUnifiedCameraMove(
                    map = map,
                    update = update,
                    paddingMode = paddingMode,
                    intent = intent,
                    animate = animate,
                    durationMs = durationMs,
                    callback = callback
                )
            }
        }
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
        durationMs: Int = MapConstants.FOLLOW_LOCK_ANIMATION_MS,
        callback: MapLibreMap.CancelableCallback? = null
    ) {
        MapZoomOrchestrator.applyUnifiedCameraMove(
            mapManager = mapManager,
            map = map,
            update = update,
            paddingMode = paddingMode,
            followLockPadding = MapConstants.FOLLOW_LOCK_PADDING,
            overlayAwarePadding = getMapPaddingArray(),
            intent = intent,
            onIntent = { activeCameraIntent = it },
            animate = animate,
            durationMs = durationMs,
            callback = callback
        )
    }

    private fun getMapPaddingArray(): DoubleArray {
        val bottomNav = activity?.findViewById<View>(R.id.bottomNavContainer)
        return MapPaddingCalculator.calculatePadding(
            resources = resources,
            mapRoot = view,
            trackerLabelCard = trackerLabelCard,
            rightControls = listOf(
                zoomToLatestButton,
                showMyLocationButton,
                mapToggle,
                zoomInButton,
                zoomOutButton,
                showAllTrackersButton,
                liveActiveFitButton
            ),
            geometryLoadingSpinner = geometryLoadingSpinner,
            mapTrackerInfoCard = mapTrackerInfoCard,
            bottomNavContainer = bottomNav,
            baseLeftDp = MapConstants.MAP_PADDING_LEFT_DP,
            baseTopDp = MapConstants.MAP_PADDING_TOP_DP,
            baseRightDp = MapConstants.MAP_PADDING_RIGHT_DP,
            baseBottomDp = MapConstants.MAP_PADDING_BOTTOM_DP,
            extraEdgeDp = MapConstants.MAP_PADDING_EDGE_EXTRA_DP,
            mapTrackerInfoCardHeightDp = MapConstants.MAP_TRACKER_INFO_CARD_HEIGHT_DP
        )
    }

    /** Per-edge bounds-fit padding: current map insets plus extra buffer. */
    private fun getBoundsPaddingEdgesPx(extraBoundsPaddingPx: Int): IntArray {
        return MapZoomOrchestrator.boundsPaddingEdgesFromInsets(getMapPaddingArray(), extraBoundsPaddingPx)
    }

    /**
     * Keep bounds-fit padding from consuming almost the whole viewport.
     * This prevents extreme zoom-out when overlay insets are very large.
     */
    private fun sanitizeBoundsFitPaddingPx(map: MapLibreMap, rawPaddingPx: IntArray): IntArray {
        return MapZoomOrchestrator.sanitizeBoundsFitPaddingPx(
            map = map,
            rawPaddingPx = rawPaddingPx,
            minViewportWidthFraction = MapConstants.MIN_BOUNDS_FIT_VIEWPORT_WIDTH_FRACTION,
            minViewportHeightFraction = MapConstants.MIN_BOUNDS_FIT_VIEWPORT_HEIGHT_FRACTION
        )
    }

    private fun moveCameraToFitBoundsWithMinZoomClamp(
        map: MapLibreMap,
        bounds: LatLngBounds,
        intent: CameraIntent? = null
    ) {
        MapZoomOrchestrator.moveCameraToFitBoundsWithMinZoomClamp(
            map = map,
            bounds = bounds,
            minZoom = MapConstants.MIN_ZOOM,
            minViewportWidthFraction = MapConstants.MIN_BOUNDS_FIT_VIEWPORT_WIDTH_FRACTION,
            minViewportHeightFraction = MapConstants.MIN_BOUNDS_FIT_VIEWPORT_HEIGHT_FRACTION,
            intent = intent,
            getBoundsPaddingEdgesPx = ::getBoundsPaddingEdgesPx
        ) { update, paddingMode, moveIntent, animate, durationMs, callback ->
            applyUnifiedCameraMove(
                map = map,
                update = update,
                paddingMode = paddingMode,
                intent = moveIntent,
                animate = animate,
                durationMs = durationMs,
                callback = callback
            )
        }
    }

    private fun moveCameraToFitBoundsCenteredWithMinZoomClamp(map: MapLibreMap, bounds: LatLngBounds) {
        MapZoomOrchestrator.moveCameraToFitBoundsCenteredWithMinZoomClamp(
            map = map,
            bounds = bounds,
            minZoom = MapConstants.MIN_ZOOM,
            intent = CameraIntent.BOUNDS_FIT
        ) { update, paddingMode, moveIntent, animate, durationMs, callback ->
            applyUnifiedCameraMove(
                map = map,
                update = update,
                paddingMode = paddingMode,
                intent = moveIntent,
                animate = animate,
                durationMs = durationMs,
                callback = callback
            )
        }
    }

    /**
     * Single-tracker open behavior: zoom to latest point (chevron), not full-history extent.
     */
    private fun zoomToLatestTrackPoint(map: MapLibreMap, fallbackLastPoint: List<Double>? = null) {
        MapZoomOrchestrator.zoomToLatestTrackPoint(
            trackPoints = trackPoints,
            fallbackLastPoint = fallbackLastPoint,
            followLockTargetZoom = MapConstants.FOLLOW_LOCK_TARGET_ZOOM
        ) { update, paddingMode, intent, animate, durationMs, callback ->
            applyUnifiedCameraMove(
                map = map,
                update = update,
                paddingMode = paddingMode,
                intent = intent,
                animate = animate,
                durationMs = durationMs,
                callback = callback
            )
        }
    }

    private fun moveCameraForAllTrackersWithMinZoom(
        map: MapLibreMap,
        bounds: LatLngBounds,
        coordsByTrackerId: Map<String, List<LatLng>>,
        trackers: List<Tracker>,
        fitToTrackerId: String?
    ) {
        preserveCenteredAllTrackersFit = MapZoomOrchestrator.moveCameraForAllTrackersWithMinZoom(
            map = map,
            bounds = bounds,
            coordsByTrackerId = coordsByTrackerId,
            trackers = trackers,
            fitToTrackerId = fitToTrackerId,
            minZoom = MapConstants.MIN_ZOOM,
            trackerCardFocusZoom = MapConstants.TRACKER_CARD_FOCUS_ZOOM,
            preserveCenteredAllTrackersFit = preserveCenteredAllTrackersFit,
            tag = TAG,
            minViewportWidthFraction = MapConstants.MIN_BOUNDS_FIT_VIEWPORT_WIDTH_FRACTION,
            minViewportHeightFraction = MapConstants.MIN_BOUNDS_FIT_VIEWPORT_HEIGHT_FRACTION,
            getBoundsPaddingEdgesPx = ::getBoundsPaddingEdgesPx
        ) { update, paddingMode, moveIntent, animate, durationMs, callback ->
            applyUnifiedCameraMove(
                map = map,
                update = update,
                paddingMode = paddingMode,
                intent = moveIntent,
                animate = animate,
                durationMs = durationMs,
                callback = callback
            )
        }
    }

    /**
     * Survey-style padding refresh: store default padding and optionally apply it
     * to the current camera position so UI inset changes take effect immediately.
     */
    private fun refreshMapPadding(force: Boolean = false, applyToCamera: Boolean = true) {
        val map = maplibreMap
        MapZoomOrchestrator.refreshMapPadding(
            map = map,
            mapManager = mapManager,
            targetPadding = getMapPaddingArray(),
            force = force,
            applyToCamera = applyToCamera
        ) { update, paddingMode, intent, animate, durationMs, callback ->
            val currentMap = map
            if (currentMap != null) {
                applyUnifiedCameraMove(
                    map = currentMap,
                    update = update,
                    paddingMode = paddingMode,
                    intent = intent,
                    animate = animate,
                    durationMs = durationMs,
                    callback = callback
                )
            }
        }
    }

    /**
     * Centralized padding refresh policy:
     * - Always refresh manager/default padding.
     * - Do not move camera while follow lock is active unless explicitly allowed.
     */
    private fun refreshMapPaddingForCurrentMode(force: Boolean = false, allowCameraMove: Boolean = true) {
        refreshMapPadding(
            force = force,
            applyToCamera = MapZoomOrchestrator.shouldApplyPaddingForCurrentMode(
                allowCameraMove = allowCameraMove,
                isFollowLockActive = isFollowLockActive(),
                activeCameraIntent = activeCameraIntent,
                liveActiveFitEnabled = liveActiveFitEnabled,
                showAllTrackers = showAllTrackers,
                mapViewContext = mapViewContext,
                preserveCenteredAllTrackersFit = preserveCenteredAllTrackersFit
            )
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
            if (!trackLineDirty) return@postFrameCallback
            if (!isAdded) {
                trackLineDirty = false
                return@postFrameCallback
            }
            if (maplibreMap?.style == null) {
                // Style may be reloading; keep dirty bit set and try again next frame.
                trackLineDirty = false
                scheduleTrackLineUpdate()
                return@postFrameCallback
            }
            trackLineDirty = false
            updateTrackLine()
        }
    }

    /**
     * Clear the map track and refetch only the currently selected tracker.
     * Call this when switching to the map from "View on map" so only that tracker is shown.
     * If the list provided an initial track (latest 100 points), shows it immediately then loads full geometry in background.
     */
    fun refreshTrackForSelectedTracker() {
        bumpTrackerRequestEpoch()
        liveActiveFitEnabled = false
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
        val selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(requireContext())
        val loadTrackerId = if (initial != null) initial.id else selectedTrackerId
        mapViewContext = MapViewContext.SINGLE_TRACKER

        val isSwitching = displayedTrackerId != null && displayedTrackerId != loadTrackerId

        // Immediate visual clear: hide annotation layers so old data doesn't flash.
        if (isSwitching) {
            setAnnotationLayersVisibility(false)
            mapFragment?.mapViewOrNull?.alpha = 0f
            mapFragment?.mapViewOrNull?.animate()?.alpha(1f)?.setDuration(200)?.setStartDelay(50)?.start()
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
                    applyInitialTargetTracker(initial, loadTrackerId, selectedTrackerId)
                }
            }, 50)
        } else {
            applyInitialTargetTracker(initial, loadTrackerId, selectedTrackerId)
        }
    }

    private fun setAnnotationLayersVisibility(visible: Boolean) {
        maplibreMap?.style?.let { MapLayerVisibility.setAnnotationLayersVisibility(it, visible) }
    }

    private fun setAllTrackLayersVisibility(visible: Boolean) {
        maplibreMap?.style?.let { MapLayerVisibility.setAllTrackLayersVisibility(it, visible) }
    }

    private fun clearAllTrackSources() {
        maplibreMap?.style?.let { MapLayerVisibility.clearAllTrackSources(it) }
    }

    /**
     * Fit map to the given group's trackers (show only those tracks and fit bounds).
     * Call when user taps "View group on map" from group detail.
     * @param zoomToTrackerId when set (e.g. user tapped a single tracker in the group), camera fits that tracker only; otherwise fits entire group.
     */
    fun refreshMapForGroup(group: Group?, zoomToTrackerId: String? = null) {
        if (group == null) return
        bumpTrackerRequestEpoch()
        MapGroupRefreshHandler.refresh(
            group = group,
            zoomToTrackerId = zoomToTrackerId,
            context = requireContext(),
            scope = mainScope,
            callbacks = MapGroupRefreshCallbacks(
                isAdded = { isAdded },
                getMap = { maplibreMap },
                getStyle = { maplibreMap?.style },
                setPendingGroup = { g, z ->
                    pendingGroupForMap = g
                    pendingGroupZoomToTrackerId = z
                },
                setLiveActiveFitEnabled = { liveActiveFitEnabled = it },
                clearMultiTrackContextState = { clearMultiTrackContextState() },
                setMapViewContext = { mapViewContext = it },
                setDisplayedGroupName = { displayedGroupName = it },
                setCurrentGroupForMap = { currentGroupForMap = it },
                setActiveCameraIntent = { activeCameraIntent = it },
                suppressStandaloneAutoZoomForTrackerFocus = { suppressStandaloneAutoZoomForTrackerFocus() },
                setShowAllTrackers = { showAllTrackers = it },
                clearMapSelection = { clearMapSelection() },
                clearAllTrackSources = { clearAllTrackSources() },
                setAllTrackLayersVisibility = { setAllTrackLayersVisibility(it) },
                setAnnotationLayersVisibility = { setAnnotationLayersVisibility(it) },
                clearTrackPointsAndDisplayedTracker = {
                    trackPoints.clear()
                    trackTimestamps.clear()
                    displayedTracker = null
                    displayedTrackerId = null
                    displayedTrackerName = null
                },
                updateTrackLine = { updateTrackLine() },
                stopLiveTrackStreaming = { stopLiveTrackStreaming() },
                updateTrackerLabel = { updateTrackerLabel() },
                updateZoomToLatestButtonState = { updateZoomToLatestButtonState() },
                startLiveTrackStreamingForTrackerSet = { startLiveTrackStreamingForTrackerSet(it) },
                applyAllTrackersToMap = { t, c, m, s, fit, fitId, liveOnly ->
                    applyAllTrackersToMap(t, c, m, s, fit, fitId, liveOnly)
                },
                getLiveActiveFitEnabled = { liveActiveFitEnabled },
                getShowAllTrackers = { showAllTrackers }
            )
        )
    }

    private fun loadAllTrackersAndApply() {
        bumpTrackerRequestEpoch()
        liveActiveFitEnabled = false
        clearMultiTrackContextState()
        val map = maplibreMap
        if (map == null) {
            pendingShowAllTrackers = true
            showAllTrackers = true
            mapViewContext = MapViewContext.SINGLE_TRACKER
            updateTrackerLabel()
            return
        }
        val style = map.style
        if (style == null) {
            // Keep intent; onMapReady/style-ready path will replay all-trackers load.
            pendingShowAllTrackers = true
            showAllTrackers = true
            mapViewContext = MapViewContext.SINGLE_TRACKER
            updateTrackerLabel()
            return
        }
        val callbacks = MapAllTrackersCallbacks(
            isAdded = { isAdded },
            onEmpty = {
                stopLiveTrackStreaming()
                showAllTrackers = true
                clearAllTrackSources()
                setAllTrackLayersVisibility(true)
                setAnnotationLayersVisibility(false)
                updateTrackerLabel()
                updateZoomToLatestButtonState()
            },
            onHasTrackers = { trackers ->
                showAllTrackers = true
                startLiveTrackStreamingForTrackerSet(trackers.map { it.id }.toSet())
                applyAllTrackersToMap(trackers, emptyMap(), map, style, fitBounds = true)
            },
            onHasGeometry = { trackers, coordsById ->
                mainScope.launch {
                    if (!isAdded || !showAllTrackers) return@launch
                    applyAllTrackersToMap(
                        trackers,
                        coordsById,
                        map,
                        style,
                        fitBounds = false
                    )
                }
            }
        )
        MapAllTrackersFlow.loadAllTrackersAndApply(requireContext(), callbacks)
    }

    /** Cached for refreshing point icons when selection changes (all-trackers or group map). */
    private var lastAllTrackers: List<Tracker>? = null
    private var lastAllTrackersCoordsById: Map<String, List<List<Double>>>? = null

    /**
     * Rehydrate all-trackers sources after style reload when we have local tail cache.
     * Returns false when cache is too thin, so caller can fall back to network reload.
     */
    private fun restoreAllTrackersFromCacheIfAvailable(map: MapLibreMap, style: Style): Boolean {
        val result = MapAllTrackersFlow.restoreAllTrackersFromCacheIfAvailable(
            lastAllTrackers,
            lastAllTrackersCoordsById,
            multiTrackCoordsCache
        ) ?: return false
        applyAllTrackersToMap(
            trackers = result.first,
            coordsById = result.second,
            map = map,
            style = style,
            fitBounds = false
        )
        return true
    }

    private fun applyAllTrackersToMap(
        trackers: List<Tracker>,
        coordsById: Map<String, List<List<Double>>>,
        map: MapLibreMap,
        style: Style,
        fitBounds: Boolean,
        fitToTrackerId: String? = null,
        liveActiveOnlyFit: Boolean = false
    ) {
        lastAllTrackers = trackers
        val outlineColor = String.format(
            "#%06X",
            0xFFFFFF and ContextCompat.getColor(requireContext(), R.color.track_line_outline)
        )
        val defaultColor = defaultTrackerColorHex(requireContext()).let { if (it.startsWith("#")) it else "#$it" }
        val renderData = MapMultiTrackRenderer.buildRenderData(
            trackers = trackers,
            coordsById = coordsById,
            selectedTrackerId = selectedMapTracker?.id,
            style = style,
            defaultColor = defaultColor,
            outlineColor = outlineColor,
            maxJumpMeters = MapConstants.MAX_JUMP_METERS,
            seedCoordsFromLastPoint = { tracker -> MapStreamingDataHelper.seedCoordsFromLastPoint(tracker, ::trackerLastUpdateMs) },
            addTrackerPropertiesToPointFeature = { feature, tracker, lat, lon, lastUpdateMs ->
                addTrackerPropertiesToPointFeature(feature, tracker, lat, lon, lastUpdateMs)
            },
            ensureArrowImageInStyle = { mapStyle, hexColor, chevronOnly ->
                ensureArrowImageInStyle(mapStyle, hexColor, chevronOnly)
            }
        )
        multiTrackCoordsCache.clear()
        multiTrackCoordsCache.putAll(renderData.normalizedCoordsById)
        lastAllTrackersCoordsById = renderData.normalizedCoordsById.mapValues { it.value.toList() }

        style.getSourceAs<GeoJsonSource>(MapConstants.ALL_TRACKS_SOURCE_ID)
            ?.setGeoJson(FeatureCollection.fromFeatures(renderData.lineFeatures))
        style.getSourceAs<GeoJsonSource>(MapConstants.ALL_TRACKS_POINTS_SOURCE_ID)
            ?.setGeoJson(FeatureCollection.fromFeatures(renderData.pointFeatures))
        setAllTrackLayersVisibility(true)
        setAnnotationLayersVisibility(false)
        updateTrackerLabel()
        updateZoomToLatestButtonState()

        if (fitBounds) {
            var fitTrackers = trackers
            var fitCoordsByTrackerId: Map<String, List<LatLng>> = renderData.coordsByTrackerId
            var boundsCoords = if (fitToTrackerId != null) {
                renderData.coordsByTrackerId[fitToTrackerId] ?: emptyList()
            } else {
                renderData.allCoords
            }
            if (liveActiveOnlyFit && fitToTrackerId == null && isLiveActiveFitAvailable()) {
                val nowMs = System.currentTimeMillis()
                val activeTrackerIds = trackers.filter { tracker ->
                    val lastUpdateMs = resolveTrackerLastUpdateMsForGroupFit(tracker, renderData.normalizedCoordsById[tracker.id])
                    lastUpdateMs != null && (nowMs - lastUpdateMs) <= MapConstants.LIVE_ACTIVE_TRACKER_WINDOW_MS
                }.map { it.id }.toSet()
                if (activeTrackerIds.isNotEmpty()) {
                    fitTrackers = trackers.filter { it.id in activeTrackerIds }
                    fitCoordsByTrackerId = renderData.coordsByTrackerId.filterKeys { it in activeTrackerIds }
                    boundsCoords = fitCoordsByTrackerId.values.flatten()
                }
            }
            if (boundsCoords.isNotEmpty()) {
                if (boundsCoords.size >= 2) {
                    val boundsBuilder = LatLngBounds.Builder()
                    boundsCoords.forEach { boundsBuilder.include(it) }
                    val bounds = boundsBuilder.build()
                    moveCameraForAllTrackersWithMinZoom(
                        map, bounds, fitCoordsByTrackerId, fitTrackers, fitToTrackerId
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

    private fun onLiveActiveFitButtonClick() {
        if (!isLiveActiveFitToggleEnabled()) return
        if (liveActiveFitEnabled) return
        val enabling = !liveActiveFitEnabled
        if (enabling) {
            disableFollowLockForLiveActiveFit()
        }
        liveActiveFitEnabled = enabling
        updateLiveActiveFitButtonUi()
        if (liveActiveFitEnabled) {
            applyLiveActiveFitNow()
        } else {
            if (activeCameraIntent == CameraIntent.SINGLE_TRACKER_FOCUS) {
                activeCameraIntent = CameraIntent.NONE
            }
            refreshMapPaddingForCurrentMode(force = true, allowCameraMove = true)
        }
    }

    private fun disableFollowLockForLiveActiveFit() {
        if (!followLockEnabled && lockTarget == null) return
        followLockEnabled = false
        lockTarget = null
        followLockNeedsInitialZoom = false
        maplibreMap?.let { LocationComponentHelper.setCameraTracking(it, enabled = false) }
        updateFollowLockButton()
    }

    private fun disableLiveActiveFitForFollowLock() {
        if (!liveActiveFitEnabled) return
        liveActiveFitEnabled = false
        updateLiveActiveFitButtonUi()
    }

    private fun disableLiveActiveFitForManualCameraInteraction() {
        if (!liveActiveFitEnabled) return
        liveActiveFitEnabled = false
        liveStreamCoordinator.cancelSingleLiveFit()
        if (activeCameraIntent == CameraIntent.SINGLE_TRACKER_FOCUS) {
            activeCameraIntent = CameraIntent.NONE
        }
        updateLiveActiveFitButtonUi()
        // Restore default overlay-aware padding state without forcing a new camera move mid-gesture.
        refreshMapPaddingForCurrentMode(force = true, allowCameraMove = false)
    }

    private fun updateLiveActiveFitButtonUi() {
        val trackingRunning = TrackingService.isRunning
        val visible = !trackingRunning && isLiveActiveFitAvailable()
        val enabled = isLiveActiveFitToggleEnabled()
        if ((!enabled || trackingRunning) && liveActiveFitEnabled) {
            liveActiveFitEnabled = false
        }
        liveActiveFitButton.visibility = if (visible) View.VISIBLE else View.GONE
        liveActiveFitButton.isEnabled = enabled
        liveActiveFitButton.alpha = 1f
        val primaryBlue = ContextCompat.getColor(requireContext(), R.color.primary_blue)
        liveActiveFitButton.setCardBackgroundColor(
            if (enabled) primaryBlue
            else Color.argb(0x99, Color.red(primaryBlue), Color.green(primaryBlue), Color.blue(primaryBlue))
        )
        liveActiveFitButtonIcon.setImageResource(
            if (liveActiveFitEnabled) R.drawable.ic_live_active_fit_on else R.drawable.ic_live_active_fit_off
        )
        liveActiveFitButtonIcon.contentDescription = if (liveActiveFitEnabled) {
            getString(R.string.live_active_fit_disable)
        } else {
            getString(R.string.live_active_fit_enable)
        }
    }

    private fun applyLiveActiveFitNow() {
        if (!isAdded || !liveActiveFitEnabled || !isLiveActiveFitAvailable()) return
        if (!showAllTrackers && mapViewContext != MapViewContext.GROUP) {
            applySingleTrackerLiveFitNow()
            return
        }
        val trackers = lastAllTrackers ?: return
        val map = maplibreMap ?: return
        val style = map.style ?: return
        applyAllTrackersToMap(
            trackers = trackers,
            coordsById = multiTrackCoordsCache.mapValues { it.value.toList() },
            map = map,
            style = style,
            fitBounds = true,
            liveActiveOnlyFit = true
        )
    }

    private fun applySingleTrackerLiveFitNow() {
        if (!isAdded || !liveActiveFitEnabled) return
        if (showAllTrackers || mapViewContext == MapViewContext.GROUP) return
        val map = maplibreMap ?: return
        val mgr = mapManager ?: return
        val trackerPoint = trackPoints.lastOrNull()
        val gpsPoint = if (showMyLocationEnabled) {
            lastStandaloneLocation?.takeIf { isFreshStandaloneFix(it) }?.let { LatLng(it.latitude, it.longitude) }
        } else {
            null
        }
        // In GPS mode, live-fit is defined as tracker + GPS.
        // If we don't currently have a fresh GPS fix, avoid snapping to tracker-only.
        // The next location callback will re-run live-fit automatically.
        if (showMyLocationEnabled && gpsPoint == null) return
        val points = mutableListOf<LatLng>()
        trackerPoint?.let { points.add(it) }
        gpsPoint?.let { points.add(it) }
        if (points.isEmpty()) return
        val paddingSnapshot = getMapPaddingArray()
        val paddingSnapshotPx = intArrayOf(
            paddingSnapshot[0].toInt(),
            paddingSnapshot[1].toInt(),
            paddingSnapshot[2].toInt(),
            paddingSnapshot[3].toInt()
        )
        if (points.size == 1) {
            activeCameraIntent = CameraIntent.SINGLE_TRACKER_FOCUS
            mgr.moveCameraWithPadding(
                map,
                CameraUpdateFactory.newLatLngZoom(points.single(), MapConstants.TRACKER_CARD_FOCUS_ZOOM),
                paddingSnapshot
            )
            return
        }
        val boundsBuilder = LatLngBounds.Builder()
        points.forEach { boundsBuilder.include(it) }
        val bounds = boundsBuilder.build()
        val fitPaddingPx = sanitizeBoundsFitPaddingPx(map, paddingSnapshotPx)
        val fitPadding = doubleArrayOf(
            fitPaddingPx[0].toDouble(),
            fitPaddingPx[1].toDouble(),
            fitPaddingPx[2].toDouble(),
            fitPaddingPx[3].toDouble()
        )
        val boundsUpdate = CameraUpdateFactory.newLatLngBounds(
            bounds,
            fitPaddingPx[0],
            fitPaddingPx[1],
            fitPaddingPx[2],
            fitPaddingPx[3]
        )
        activeCameraIntent = CameraIntent.SINGLE_TRACKER_FOCUS
        mgr.moveCameraWithPadding(map, boundsUpdate, fitPadding)
    }

    private fun resolveTrackerLastUpdateMsForGroupFit(
        tracker: Tracker,
        normalizedCoords: List<List<Double>>?
    ): Long? {
        return MapLiveActiveFitController.resolveTrackerLastUpdateMsForGroupFit(
            tracker = tracker,
            normalizedCoords = normalizedCoords,
            lastKnownUpdateByTrackerId = lastKnownUpdateTimeMsByTrackerId,
            trackerLastUpdateMs = ::trackerLastUpdateMs
        )
    }

    private fun isLiveActiveFitAvailable(): Boolean {
        return MapLiveActiveFitController.isLiveActiveFitAvailable(
            showAllTrackers = showAllTrackers,
            mapViewContext = mapViewContext,
            hasTrackPoints = trackPoints.isNotEmpty()
        )
    }

    private fun isLiveActiveFitToggleEnabled(): Boolean {
        return MapLiveActiveFitController.isLiveActiveFitToggleEnabled(
            available = isLiveActiveFitAvailable(),
            showAllTrackers = showAllTrackers,
            mapViewContext = mapViewContext,
            showMyLocationEnabled = showMyLocationEnabled
        )
    }

    private fun setupMapTapListener(map: MapLibreMap) {
        map.addOnMapClickListener { latLng ->
            if (showAllTrackers) {
                val screen = map.projection.toScreenLocation(latLng)
                val point = PointF(screen.x, screen.y)
                val features = map.queryRenderedFeatures(point, MapConstants.ALL_TRACKS_POINTS_LAYER_ID)
                if (features.isEmpty()) {
                    clearMapSelection()
                    return@addOnMapClickListener false
                }
                val feature = MapTapSelectionHandler.selectNearestFeature(map, point, features) ?: return@addOnMapClickListener false
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
            val positionFeatures = map.queryRenderedFeatures(point, MapConstants.TRACK_POSITION_LAYER_ID)
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
            // In single-tracker mode, also allow selecting by tapping near the latest point.
            if (trackPoints.isNotEmpty()) {
                val last = trackPoints.last()
                if (MapTapSelectionHandler.isTapNearPoint(map, latLng, last, MapConstants.TAP_NEAR_POINT_PX)) {
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
        val id = displayedTrackerId!!
        val defaultHexColor = defaultTrackerColorHex(requireContext()).let { if (it.startsWith("#")) it else "#$it" }
        return MapSelectionUtils.selectedFromDisplayedState(
            displayedTrackerId = id,
            displayedTrackerName = displayedTrackerName,
            displayedTrackerIsOwner = displayedTrackerIsOwner,
            lat = lat,
            lon = lon,
            currentTrackerColor = currentTrackerColor,
            defaultHexColor = defaultHexColor,
            lastStreamedPointTimeMs = lastStreamedPointTimeMs,
            lastCachedUpdateTimeMs = lastCachedUpdateTimeMs,
            displayedTrackerLastUpdateMs = displayedTracker?.takeIf { it.id == id }?.let { trackerLastUpdateMs(it) },
            lastKnownUpdateMs = lastKnownUpdateTimeMsByTrackerId[id]
                ?: TrackerRepository.getTrackerFromCache(id)?.let { trackerLastUpdateMs(it) }
        )
    }

    private fun selectedMapTrackerFromFeature(feature: Feature): SelectedMapTracker? {
        val defaultHexColor = defaultTrackerColorHex(requireContext()).let { if (it.startsWith("#")) it else "#$it" }
        return MapSelectionUtils.selectedFromFeature(
            feature = feature,
            defaultHexColor = defaultHexColor,
            lastKnownById = lastKnownUpdateTimeMsByTrackerId,
            resolveTrackerIsOwner = { trackerId -> resolveTrackerIsOwner(null, trackerId) }
        )
    }

    /** Some payloads may omit is_owner. Resolve strictly from payload/cache without id-based shortcuts. */
    private fun resolveTrackerIsOwner(tracker: Tracker?, trackerId: String): Boolean {
        tracker?.is_owner?.let { return it }
        TrackerRepository.getTrackerFromCache(trackerId)?.is_owner?.let { return it }
        return false
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
        val source = style.getSourceAs<GeoJsonSource>(MapConstants.ALL_TRACKS_POINTS_SOURCE_ID) ?: return
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
                val lastUpdateMs = lastN.lastOrNull()?.let { MapCoordinateUtils.timestampFromCoordinateMs(it) }
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
        val state = MapTrackerInfoCardController.computeUiState(
            selection = sel,
            currentGroupForMap = currentGroupForMap,
            wasInfoCardVisible = wasInfoCardVisible,
            selectionIdChanged = selectionIdChanged,
            context = requireContext()
        )
        mapTrackerInfoCard.visibility = View.VISIBLE
        mapTrackerInfoName.text = state.nameText
        mapTrackerInfoCoords.text = state.coordsText
        mapTrackerInfoLastUpdated.text = state.lastUpdatedText
        mapTrackerInfoViewParams.contentDescription = state.viewParamsContentDescription
        mapTrackerInfoViewInList.contentDescription = state.viewInListContentDescription
        mapTrackerInfoFocus.visibility = if (showAllTrackers || mapViewContext == MapViewContext.GROUP) {
            View.VISIBLE
        } else {
            View.GONE
        }
        updateFollowLockButton()
        refreshMapPaddingForCurrentMode(force = true, allowCameraMove = false)
        if (state.shouldRecenterOnOpen) {
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
        disableLiveActiveFitForFollowLock()
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
        displayedTrackerId = sel.id
        displayedTrackerName = sel.name.ifEmpty { null }
        displayedTrackerIsOwner = sel.isOwner
        displayedGroupName = null
        mapViewContext = MapViewContext.SINGLE_TRACKER
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
        MapPointFeatureHelper.addTrackerPropertiesToPointFeature(
            feature, tracker, lat, lon, lastUpdateMs,
            requireContext(),
            defaultTrackerColorHex(requireContext()).let { if (it.startsWith("#")) it else "#$it" },
            ::resolveTrackerIsOwner,
            ::trackerLastUpdateMs
        )
    }

    /** @param chevronOnly when true (all-track mode), use only the chevron icon without the white circle. */
    private fun ensureArrowImageInStyle(style: Style, hexColor: String, chevronOnly: Boolean = false) {
        MapPointFeatureHelper.ensureArrowImageInStyle(requireContext(), style, hexColor, chevronOnly)
    }

    private fun applyCoordinatesPreview(
        coordinates: List<List<Double>>,
        forceReplace: Boolean
    ): Boolean {
        val applied = MapHistoryUtils.applyCoordinatesPreview(
            coordinates = coordinates,
            forceReplace = forceReplace,
            trackPoints = trackPoints,
            trackTimestamps = trackTimestamps
        )
        if (!applied) return false
        updateTrackLine()
        setAnnotationLayersVisibility(true)
        updateZoomToLatestButtonState()
        return true
    }

    private fun buildSingleTrackFetchCallbacks(): MapSingleTrackFetchCallbacks {
        return MapSingleTrackFetchCallbacks(
            getScope = { mainScope },
            getDisplayedTrackerId = { displayedTrackerId },
            getSelectedTrackerId = { SelectedTrackerPrefs.selectedTrackerId(requireContext()) },
            getShowAllTrackers = { showAllTrackers },
            getMapViewContext = { mapViewContext },
            getTrackPointsEmpty = { trackPoints.isEmpty() },
            getCoordinatesFetchInFlightTrackerId = { coordinatesFetchInFlightTrackerId },
            setCoordinatesFetchInFlightTrackerId = { coordinatesFetchInFlightTrackerId = it },
            getGeometryFetchInFlightTrackerId = { geometryFetchInFlightTrackerId },
            setGeometryFetchInFlightTrackerId = { geometryFetchInFlightTrackerId = it },
            setGeometryLoadingInProgress = { geometryLoadingInProgress = it },
            updateBottomRightSpinner = { updateBottomRightSpinner() },
            onSkipped = {
                updateZoomToLatestButtonState()
                updateTrackerLabel()
            },
            onSetZoomToTrackAfterLoad = { zoomToTrackAfterLoad = it },
            onSeededFromPreview = { tracker, coords, forceReplace, accuracyMeters ->
                handleSeededFromPreview(tracker, coords, forceReplace, accuracyMeters)
            },
            onSeededFromNetwork = { coords, pointParams ->
                handleSeededFromNetwork(coords, pointParams)
            },
            getIsAdded = { isAdded },
            getTrackerRequestEpoch = { trackerRequestEpoch },
            onGeometryLoaded = { tracker, trackerId, forceReplace ->
                handleSingleTrackGeometryLoaded(tracker, trackerId, forceReplace)
            }
        )
    }

    private fun handleSeededFromPreview(
        tracker: Tracker?,
        coords: List<List<Double>>,
        forceReplace: Boolean,
        accuracyMeters: Float?
    ): Boolean {
        if (tracker != null) {
            lastCachedUpdateTimeMs = trackerLastUpdateMs(tracker)
            currentTrackerColor = (tracker.color ?: defaultTrackerColorHex(requireContext())).let { if (it.startsWith("#")) it else "#$it" }
            displayedTracker = tracker
            displayedTrackerId = tracker.id
            displayedTrackerName = tracker.name
            displayedTrackerIsOwner = tracker.isOwner()
        }
        accuracyMeters?.let { lastStreamedAccuracyMeters = it }
        val applied = applyCoordinatesPreview(coords, forceReplace)
        if (applied) updateTrackerLabel()
        return applied
    }

    private fun handleSeededFromNetwork(
        coords: List<List<Double>>,
        pointParams: List<Map<String, Any?>>?
    ) {
        val applied = applyCoordinatesPreview(coords, forceReplace = trackPoints.isEmpty())
        if (applied) {
            pointParams?.lastOrNull()?.get("acc")
                ?.let { (it as? Number)?.toFloat() }
                ?.takeIf { it > 0f }
                ?.let { lastStreamedAccuracyMeters = it }
            updateTrackerLabel()
        }
    }

    private fun handleSingleTrackGeometryLoaded(
        tracker: Tracker?,
        trackerId: String,
        forceReplace: Boolean
    ) {
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
        displayedGroupName = null
        mapViewContext = MapViewContext.SINGLE_TRACKER
        if (tracker != null) {
            val resolvedColor = (tracker.color ?: defaultTrackerColorHex(requireContext())).let { if (it.startsWith("#")) it else "#$it" }
            currentTrackerColor = resolvedColor
            maplibreMap?.style?.let { ensureArrowImageInStyle(it, resolvedColor, chevronOnly = false) }
            (tracker.point_params?.lastOrNull()?.get("acc") as? Number)?.toFloat()?.takeIf { it > 0f }
                ?.let { lastStreamedAccuracyMeters = it }
        }
        val coords = tracker?.geometry?.coordinates
        if (coords != null) {
            val normalizedCoords = MapCoordinateUtils.normalizeRawCoordinates(coords)
            val isExternalStreaming = MapDataLoader.isExternalStreaming(
                forceReplace = forceReplace,
                hasTrackPoints = trackPoints.isNotEmpty(),
                displayedTrackerId = displayedTrackerId
            )
            if (isExternalStreaming || forceReplace || trackPoints.isEmpty()) {
                MapHistoryUtils.applyGeometryToTrack(
                    normalizedCoords = normalizedCoords,
                    mergeExternalStreaming = isExternalStreaming,
                    trackPoints = trackPoints,
                    trackTimestamps = trackTimestamps
                )
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
                zoomToLatestTrackPoint(map, tracker.last_point)
            }
            if (!showAllTrackers && trackPoints.isNotEmpty() && !showMyLocationEnabled) {
                disableLiveActiveFitForFollowLock()
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

    private fun seedTrackFromCacheOrTail(
        trackerId: String,
        initialTracker: Tracker? = null,
        allowCoordinatesNetwork: Boolean
    ) {
        MapSingleTrackFetch.seedFromCacheOrTail(
            requireContext(),
            trackerId,
            initialTracker,
            allowCoordinatesNetwork,
            buildSingleTrackFetchCallbacks()
        )
    }

    private fun applyInitialTargetTracker(
        initial: Tracker?,
        loadTrackerId: String,
        selectedTrackerId: String
    ) {
        // Set metadata and initial points immediately
        lastStreamedPointTimeMs = null
        lastStreamedAccuracyMeters = null
        val selectedName = SelectedTrackerPrefs.selectedTrackerName(requireContext())
        val initialMeta = MapDataLoader.buildInitialTargetMeta(
            initial = initial,
            selectedTrackerId = selectedTrackerId,
            selectedTrackerName = selectedName,
            baseTrackerColor = defaultTrackerColorHex(requireContext()),
            trackerLastUpdateMs = ::trackerLastUpdateMs
        )
        displayedTracker = initialMeta.displayedTracker
        displayedTrackerId = initialMeta.displayedTrackerId
        displayedTrackerName = initialMeta.displayedTrackerName
        displayedTrackerIsOwner = initialMeta.displayedTrackerIsOwner
        displayedGroupName = initialMeta.displayedGroupName
        mapViewContext = initialMeta.mapViewContext
        lastCachedUpdateTimeMs = initialMeta.lastCachedUpdateTimeMs
        currentTrackerColor = initialMeta.currentTrackerColor
        lastStreamedAccuracyMeters = initialMeta.lastStreamedAccuracyMeters

        if (initial != null) {
            // Position camera to latest point (chevron); do not fit full-history extent.
            val map = maplibreMap
            if (map != null && MapDataLoader.shouldAllowTrackerCameraMoveInMyLocation(showMyLocationEnabled, activeCameraIntent)) {
                zoomToLatestTrackPoint(map, initial.last_point)
                zoomToTrackAfterLoad = false
            }
            updateZoomToLatestButtonState()
        }

        updateTrackerLabel()
        // Start streaming immediately — don't wait for full geometry to load
        startLiveTrackStreamingForDisplayedTracker()
        seedTrackFromCacheOrTail(loadTrackerId, initialTracker = initial, allowCoordinatesNetwork = true)
        fetchFullGeometryAndApply(loadTrackerId)
    }

    /**
     * Refetch and redraw the selected tracker's track without moving the camera.
     */
    fun restoreTrackForSelectedTracker() {
        bumpTrackerRequestEpoch()
        liveActiveFitEnabled = false
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
        mapViewContext = MapViewContext.SINGLE_TRACKER
        restoreOnlyNoZoom = true
        followLockEnabled = false
        updateFollowLockButton()
        lastCachedUpdateTimeMs = null
        val selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(requireContext())
        val selectedTrackerName = SelectedTrackerPrefs.selectedTrackerName(requireContext())
        if (selectedTrackerId.isEmpty()) {
            updateTrackLine()
            updateZoomToLatestButtonState()
            updateTrackerLabel()
            return
        }
        displayedTrackerId = selectedTrackerId
        displayedTrackerName = selectedTrackerName.ifEmpty { null }
        displayedTrackerIsOwner = true
        mapViewContext = MapViewContext.SINGLE_TRACKER
        updateTrackerLabel()
        fetchHistory()
    }

    private fun fetchHistory() {
        liveActiveFitEnabled = false
        if (mapViewContext == MapViewContext.GROUP) {
            updateZoomToLatestButtonState()
            updateTrackerLabel()
            return
        }
        if ((activity as? MainActivity)?.initialTrackForMap != null) {
            refreshTrackForSelectedTracker()
            return
        }
        MapSingleTrackFetch.loadHistory(requireContext(), buildSingleTrackFetchCallbacks())
    }

    private fun fetchFullGeometryAndApply(trackerId: String, forceReplace: Boolean = false) {
        MapSingleTrackFetch.fetchFullGeometry(requireContext(), trackerId, forceReplace, buildSingleTrackFetchCallbacks())
    }

    /** Start live streaming for the currently displayed single tracker. */
    private fun startLiveTrackStreamingForDisplayedTracker() {
        if (TrackingService.isRunning) {
            stopLiveTrackStreaming()
            return
        }
        MapStreamingServiceHelper.updateStreamingForDisplayedTracker(
            displayedTrackerId,
            displayedTrackerName,
            mapViewContext,
            startStreaming = { ids, name -> startLiveTrackStreamingForTrackerSet(ids, name) },
            stopStreaming = { stopLiveTrackStreaming() }
        )
    }

    /** Start live streaming for a set of trackers (group/all-trackers context). */
    private fun startLiveTrackStreamingForTrackerSet(trackerIds: Set<String>, trackerName: String? = null) {
        if (TrackingService.isRunning) {
            stopLiveTrackStreaming()
            return
        }
        val cleanedIds = MapStreamingServiceHelper.startStreaming(requireContext(), trackerIds, trackerName)
        if (cleanedIds == null) {
            stopLiveTrackStreaming()
            return
        }
        activeStreamedTrackerIds = cleanedIds
    }

    private fun stopLiveTrackStreaming() {
        activeStreamedTrackerIds = emptySet()
        MapStreamingServiceHelper.stopStreaming(requireContext())
    }

    private fun updateTrackLine() {
        val style = maplibreMap?.style ?: return
        val lineColor = currentTrackerColor ?: defaultTrackerColorHex(requireContext())
        val outlineColorInt = ContextCompat.getColor(requireContext(), R.color.track_line_outline)
        val outlineColor = String.format("#%06X", 0xFFFFFF and outlineColorInt)
        val hasLine = MapTrackLineUpdater.updateTrackLine(
            style = style,
            trackSourceId = MapConstants.TRACK_SOURCE_ID,
            trackPoints = trackPoints,
            lineColor = lineColor,
            outlineColor = outlineColor,
            maxJumpMeters = MapConstants.MAX_JUMP_METERS
        )
        if (!hasLine && trackPoints.size < 2) {
            applyPositionSymbolUpdate()
            return
        }
        applyPositionSymbolUpdate()
    }

    private fun applyPositionSymbolUpdate() {
        if (!isAdded) return
        val map = maplibreMap ?: return
        val style = map.style ?: return
        MapTrackLineUpdater.applyPositionSymbolUpdate(
            context = requireContext(),
            map = map,
            style = style,
            trackPoints = trackPoints,
            currentTrackerColor = currentTrackerColor,
            showMyLocationEnabled = showMyLocationEnabled,
            lastStreamedAccuracyMeters = lastStreamedAccuracyMeters,
            trackingServiceAccuracyMeters = TrackingService.lastAccuracyMeters,
            trackPositionSourceId = MapConstants.TRACK_POSITION_SOURCE_ID,
            trackPositionAccuracySourceId = MapConstants.TRACK_POSITION_ACCURACY_SOURCE_ID,
            ensureArrowImage = { mapStyle, hexColor ->
                ensureArrowImageInStyle(mapStyle, hexColor, chevronOnly = false)
            }
        )
    }

    /**
     * Build a geodesic polygon around [center] with [radiusMeters] radius.
     * Rendering this geometry avoids zoom-time CircleLayer radius jitter.
     */
    private fun getTrackDirectionDegrees(points: List<LatLng>): Float =
        MapTrackGeometryRenderer.getTrackDirectionDegrees(points)

    private fun splitTrackIntoSegments(points: List<LatLng>): List<List<LatLng>> =
        MapTrackGeometryRenderer.splitTrackIntoSegments(points, MapConstants.MAX_JUMP_METERS)

    companion object {
        private const val TAG = "MapFragment"
        private const val KEY_FOLLOW_LOCK = "follow_lock"
        private const val KEY_SHOW_MY_LOCATION = "show_my_location"
    }

}
