package com.geovault.tracker.fragments.map

import android.annotation.SuppressLint
import android.view.animation.AnimationUtils
import android.view.accessibility.AccessibilityManager
import android.graphics.Color
import android.content.*
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.view.Choreographer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.geovault.common.LoadingSpinner
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import com.geovault.common.map.GeoVaultMapFragment
import com.geovault.common.map.LocationComponentHelper
import com.geovault.common.map.MapLibreManager
import com.geovault.tracker.defaultTrackerColorHex
import com.geovault.tracker.Group
import com.geovault.tracker.R
import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.TrackingService
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackUpdateHelper
import com.geovault.tracker.GeoJsonLineString
import com.geovault.tracker.lastUpdateMs
import com.geovault.tracker.settings.TrackerSettingsRepository
import com.geovault.tracker.fragments.TrackersListFragment
import com.geovault.tracker.navigation.navHost
import com.geovault.tracker.services.TrackingRuntimeStateStore
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
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MapFragment : Fragment() {
    @Inject
    lateinit var settingsRepository: TrackerSettingsRepository

    private val mapFlowViewModel: MapViewModel by viewModels()
    private val mapStateViewModel: MapStateViewModel by viewModels()

    private var mapFragment: GeoVaultMapFragment? = null
    private var mapManager: MapLibreManager? = null
    private var maplibreMap: MapLibreMap? = null
    private var trackPoints: MutableList<LatLng> = mutableListOf()
    private var trackTimestamps: MutableList<Long> = mutableListOf()
    /** Tracker color (hex, default from R.color.default_tracker_color) for trail and icon; set when loading tracker in fetchHistory. */
    private var currentTrackerColor: String? = null

    private lateinit var mapLoadingOverlay: View
    private lateinit var mapLoadingSpinner: LoadingSpinner
    private lateinit var trackerLabelCard: View
    private lateinit var trackerLabelIcon: ImageView
    private lateinit var trackerNameLabel: TextView
    private lateinit var resetToTrackerButton: View
    private lateinit var mapToggle: View
    private lateinit var zoomToLatestButton: View
    private lateinit var zoomToLatestButtonIcon: ImageView
    private lateinit var zoomInButton: View
    private lateinit var zoomOutButton: View
    private lateinit var bottomRightIndicatorContainer: View
    private lateinit var geometryLoadingSpinner: LoadingSpinner
    private lateinit var streamingIndicator: View
    private lateinit var gpsAccuracyWarningIndicator: ImageView
    private lateinit var lastUpdatedLabel: TextView
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
    /** In-flight geometry request token used to coalesce duplicate lifecycle fetches. */
    private var geometryFetchToken: InFlightRequestToken = InFlightRequestToken()
    /** In-flight coordinates request token used to avoid duplicate warm-start tail requests. */
    private var coordinatesFetchToken: InFlightRequestToken = InFlightRequestToken()

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
    /** True while the GPS warning icon is running its flashing animation. */
    private var gpsWarningAnimationActive = false

    private var mapReady = false
    private var followLockEnabled = false
    private var followLockNeedsInitialZoom = false
    /** When true, fetchHistory() will zoom the camera to fit the loaded track (e.g. after "View on map"). */
    private var zoomToTrackAfterLoad = false
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
    /** While non-null, single-tracker UI state from ViewModel is ignored unless it matches this tracker id. */
    private var pendingDisplayedTrackerIdOverride: String? = null
    /** In-memory history cache for multi-tracker map contexts (group/all-trackers). */
    private val multiTrackCoordsCache = mutableMapOf<String, MutableList<List<Double>>>()
    /** Single source of truth for all-trackers render/fit snapshot. */
    private var allTrackersState: AllTrackersMapState? = null
    /** Monotonic token used to reject stale single-tracker async callbacks. */
    private var trackerRequestEpoch: Long = 0L
    /** When true, user has enabled non-tracking "show my location" mode (blue dot, camera follow). */
    private var showMyLocationEnabled = false
    /** True after we have received at least one GPS fix while not tracking (used for button visibility). */
    private var hasLiveGpsFix = false
    /** True when camera is currently locked/recentered to GPS location mode. */
    private var gpsLocationLockActive = false
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

    private val liveStreamCoordinator = MapLiveStreamCoordinator(lifecycleScope)
    private var mapCommandsJob: Job? = null

    private fun applyLiveStreamPoint(
        trackId: String,
        lat: Double,
        lon: Double,
        timestampMs: Long
    ) {
        MapLiveStreamPointHandler.applyLiveStreamPoint(trackId, lat, lon, timestampMs, buildLiveStreamPointCallbacks())
    }

    private fun buildLiveStreamPointCallbacks(): MapLiveStreamPointCallbacks {
        return MapLiveStreamPointCallbacks(
            getShowAllTrackers = { showAllTrackers },
            getMapViewContext = { mapViewContext },
            getActiveStreamedTrackerIds = { activeStreamedTrackerIds },
            getLastAllTrackers = { allTrackersState?.trackers },
            getTrackerBaseCoordsForMultiContext = { tracker, trackId ->
                MapStreamingDataHelper.getTrackerBaseCoordsForMultiContext(
                    tracker, trackId, multiTrackCoordsCache, allTrackersState?.normalizedCoordsById
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
            val state = allTrackersState ?: return@stream
            val map = maplibreMap ?: return@stream
            val style = map.style ?: return@stream
            val useLiveActiveFit = liveActiveFitEnabled && isLiveActiveFitAvailable()
            applyAllTrackersToMap(
                state.trackers,
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
        allTrackersState = null
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
        mapLoadingSpinner = view.findViewById(R.id.mapLoadingSpinner)
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
        bottomRightIndicatorContainer = view.findViewById(R.id.bottomRightIndicatorContainer)
        geometryLoadingSpinner = view.findViewById(R.id.geometryLoadingSpinner)
        streamingIndicator = view.findViewById(R.id.streamingIndicator)
        gpsAccuracyWarningIndicator = view.findViewById(R.id.gpsAccuracyWarningIndicator)
        lastUpdatedLabel = view.findViewById(R.id.lastUpdatedLabel)
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
                        if (showMyLocationEnabled && gpsLocationLockActive) {
                            gpsLocationLockActive = false
                            updateShowMyLocationButtonVisibility()
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
                        updateShowMyLocationButtonVisibility()
                    }
                    mapFragment?.mapViewOrNull?.addOnDidFinishLoadingStyleListener(styleReloadListener!!)
                }
                mapReady = true
                mapLoadingOverlay.visibility = View.GONE
                mapLoadingSpinner.stop()
                refreshMapPaddingForCurrentMode(force = true)
                updateTrackLine()
                updateShowMyLocationButtonVisibility()
                if (resolveMyLocationPolicy().myLocationModeActive && pendingAutoZoomToStandaloneFix) {
                    val loc = lastStandaloneLocation
                    if (loc != null) consumePendingStandaloneAutoZoom(loc, animate = true)
                }
                if (zoomToTrackAfterLoad && trackPoints.isNotEmpty()) {
                    zoomToLatestTrackPoint(map)
                    zoomToTrackAfterLoad = false
                }
                val (deferredGroup, deferredZoom) = navHost()?.getAndClearInitialGroupAndZoomForMap() ?: Pair(null, null)
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
                if (navHost()?.hasPendingInitialTrackForMap == true) {
                    refreshTrackForSelectedTracker()
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
            val main = navHost() ?: return@setOnClickListener
            val group = currentGroupForMap
            if (group != null) {
                main.openGroupMembersAndScrollTo(group, displayedTrackerId)
            } else {
                main.openSharedAndScrollTo(displayedTrackerId)
            }
        }
        resetToTrackerButton.setOnClickListener {
            mapFlowViewModel.cancelGeometryRequest()
            restoreTrackForSelectedTracker()
        }

        val restoredState = mapStateViewModel.latestSavedState ?: MapSavedState.readFrom(savedInstanceState)
        followLockEnabled = restoredState.followLockEnabled
        showMyLocationEnabled = restoredState.showMyLocationEnabled
        showAllTrackers = restoredState.showAllTrackers
        displayedTrackerId = restoredState.displayedTrackerId
        displayedTrackerName = restoredState.displayedTrackerName
        displayedGroupName = restoredState.displayedGroupName
        mapViewContext = restoredState.mapViewContext
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
            val selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(requireContext())
            when (
                MapListRefreshPolicy.resolve(
                    showAllTrackers = showAllTrackers,
                    mapViewContext = mapViewContext,
                    selectedTrackerId = selectedTrackerId,
                    displayedTrackerId = displayedTrackerId
                )
            ) {
                MapListRefreshAction.LOAD_ALL -> {
                    loadAllTrackersAndApply()
                    return@setFragmentResultListener
                }
                MapListRefreshAction.REFRESH_SELECTED_TRACKER -> {
                    refreshTrackForSelectedTracker()
                }
                MapListRefreshAction.NO_OP -> Unit
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
        showMyLocationButton.setOnLongClickListener {
            onShowMyLocationLongClick()
            true
        }
        liveActiveFitButton.setOnClickListener { onLiveActiveFitButtonClick() }
        observeMapFlow()
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

        if (trackingRuntimeSnapshot().isRunning) {
            // Local tracking owns live updates; do not run websocket streaming in tracking mode.
            stopLiveTrackStreaming()
            if (showMyLocationEnabled) {
                showMyLocationEnabled = false
                gpsLocationLockActive = false
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

        mapFlowViewModel.startTrackPointStream()

        // Highest priority: if another screen requested a specific tracker for map,
        // consume it immediately instead of reusing previously displayed state.
        if (mapReady && navHost()?.hasPendingInitialTrackForMap == true) {
            refreshTrackForSelectedTracker()
            return
        }

        if (mapReady) {
            val decision = mapFlowViewModel.resolveResumeDecision(
                MapResumeInput(
                    trackingRunning = trackingRuntimeSnapshot().isRunning,
                    mapReady = mapReady,
                    showAllTrackers = showAllTrackers,
                    mapViewContext = mapViewContext,
                    activeStreamedTrackerIds = activeStreamedTrackerIds,
                    currentGroupTrackIds = currentGroupForMap?.track_ids?.toSet() ?: emptySet(),
                    selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(requireContext()),
                    displayedTrackerId = displayedTrackerId,
                    hasTrackPoints = trackPoints.isNotEmpty(),
                    hasPendingInitialTracker = navHost()?.hasPendingInitialTrackForMap == true
                )
            )
            when (decision) {
                MapResumeDecision.NoOp -> Unit
                MapResumeDecision.MultiContextNoStreaming -> updateTrackerLabel()
                is MapResumeDecision.StartMultiContextStreaming -> {
                    startLiveTrackStreamingForTrackerSet(decision.trackerIds)
                    updateTrackerLabel()
                }
                MapResumeDecision.ClearSingleTrackerState -> {
                    trackPoints.clear()
                    displayedTracker = null
                    displayedTrackerId = null
                    displayedTrackerName = null
                    stopLiveTrackStreaming()
                    updateTrackLine()
                    updateZoomToLatestButtonState()
                    updateTrackerLabel()
                }
                is MapResumeDecision.LoadSingleTracker -> {
                    if (displayedTrackerId != decision.trackerId || mapViewContext != MapViewContext.SINGLE_TRACKER) {
                        displayedTrackerId = decision.trackerId
                        mapViewContext = MapViewContext.SINGLE_TRACKER
                    }
                    mapFlowViewModel.handleIntent(
                        MapIntent.LoadSingleTracker(
                            trackerId = decision.trackerId,
                            forceReplace = false
                        )
                    )
                }
                MapResumeDecision.RestartDisplayedTrackerStreaming -> {
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
        mapFlowViewModel.stopTrackPointStream()
    }

    override fun onDestroyView() {
        setGpsAccuracyWarningVisible(false)
        styleReloadListener?.let { listener ->
            mapFragment?.mapViewOrNull?.removeOnDidFinishLoadingStyleListener(listener)
        }
        mapCommandsJob?.cancel()
        mapCommandsJob = null
        styleReloadListener = null
        mapFragment?.setCallback(null)
        mapFragment = null
        mapManager = null
        maplibreMap = null
        mapReady = false
        geometryFetchToken = InFlightRequestToken()
        coordinatesFetchToken = InFlightRequestToken()
        // Preserve deferred group handoff across view teardown so onMapReady can still apply it.
        clearMultiTrackContextState(clearPendingGroupIntent = false)
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val state = MapSavedState(
            followLockEnabled = followLockEnabled,
            showMyLocationEnabled = showMyLocationEnabled,
            displayedTrackerId = displayedTrackerId,
            displayedTrackerName = displayedTrackerName,
            displayedGroupName = displayedGroupName,
            showAllTrackers = showAllTrackers,
            mapViewContext = mapViewContext
        )
        mapStateViewModel.latestSavedState = state
        state.writeTo(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapFragment?.mapViewOrNull?.onLowMemory()
    }

    private fun isFollowLockActive(): Boolean = followLockEnabled && lockTarget != null

    private fun isSelectedDefaultTrackerMode(): Boolean {
        val selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(requireContext())
        return !selectedTrackerId.isNullOrEmpty() &&
            displayedTrackerId == selectedTrackerId &&
            !showAllTrackers &&
            mapViewContext == MapViewContext.SINGLE_TRACKER
    }

    private fun zoomButtonsPaddingMode(): CameraPaddingMode =
        MapZoomOrchestrator.zoomButtonsPaddingMode(activeCameraIntent, isFollowLockActive())

    private fun updateFollowLockButton() {
        val (iconResId, contentDescResId) = MapCameraController.followLockButtonContent(isFollowLockActive())
        zoomToLatestButtonIcon.setImageResource(iconResId)
        zoomToLatestButtonIcon.contentDescription = getString(contentDescResId)
        mapTrackerInfoZoomLock.setImageResource(iconResId)
        // Follow-lock changes should immediately reflect in the GPS button icon state.
        updateShowMyLocationButtonVisibility()
    }

    private fun updateZoomToLatestButtonState() {
        val hasTrack = !showAllTrackers &&
            trackPoints.isNotEmpty()
        zoomToLatestButton.visibility = if (hasTrack) View.VISIBLE else View.GONE
        if (zoomToLatestButton.visibility == View.VISIBLE) {
            zoomToLatestButton.alpha = 1f
        }
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
                liveActiveFitButton
            )
        )
        // Stack visibility/layout updates should not move camera; only update manager/default padding.
        refreshMapPaddingForCurrentMode(force = true, allowCameraMove = false)
    }

    private fun updateShowMyLocationButtonVisibility() {
        val policy = resolveMyLocationPolicy()
        maplibreMap?.let { map ->
            // Invariant: if the button is hidden for selected/default mode, GPS puck must also be disabled.
            LocationComponentHelper.setEnabled(map, policy.shouldEnablePuck)
            LocationComponentHelper.setCameraTracking(map, enabled = policy.shouldTrackGpsCamera)
        }
        val state = MapStandaloneLocationController.myLocationButtonState(
            trackingRunning = trackingRuntimeSnapshot().isRunning,
            showMyLocationEnabled = policy.myLocationModeActive,
            waitingForFix = waitingForStandaloneFix,
            gpsLockActive = policy.effectiveGpsLockActive,
            context = requireContext()
        )
        showMyLocationButton.visibility = if (policy.shouldShowButton) state.visibility else View.GONE
        if (showMyLocationButton.visibility == View.VISIBLE) {
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

    private fun resolveMyLocationPolicy(): MyLocationPolicyDecision {
        return MapMyLocationPolicy.compute(
            MyLocationPolicyInput(
                trackingRunning = trackingRuntimeSnapshot().isRunning,
                showMyLocationEnabledIntent = showMyLocationEnabled,
                isSelectedDefaultTracker = isSelectedDefaultTrackerMode(),
                gpsLockRequested = gpsLocationLockActive,
                trackerOrLiveLockActive = isFollowLockActive() || liveActiveFitEnabled
            )
        )
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

    private fun consumePendingStandaloneAutoZoom(location: Location, animate: Boolean = true) {
        val shouldAttemptAutoZoom = pendingAutoZoomToStandaloneFix &&
            !isTrackerFocusIntentActive() &&
            !suppressStandaloneAutoZoom
        val zoomApplied = if (shouldAttemptAutoZoom) {
            zoomToStandaloneLocation(location, forceZoomIn = true, animate = animate)
        } else {
            false
        }
        if (zoomApplied) gpsLocationLockActive = true
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

    private fun isFreshStandaloneFix(location: Location): Boolean {
        val now = System.currentTimeMillis()
        return location.time > 0L && (now - location.time) <= MapConstants.STANDALONE_FIX_FRESHNESS_MS
    }

    @SuppressLint("MissingPermission")
    private fun onShowMyLocationClick() {
        val navHost = navHost() ?: return
        if (!navHost.hasLocationPermission()) {
            navHost.requestLocationPermission()
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
            // Drop lock first so tap-zoom camera move is not overridden by GPS tracking mode.
            gpsLocationLockActive = false
            updateShowMyLocationButtonVisibility()
            suppressStandaloneAutoZoom = false
            lastStandaloneLocation?.let { loc ->
                gpsLocationLockActive = zoomToStandaloneLocation(
                    loc,
                    forceZoomIn = true,
                    animate = false
                )
            }
            updateShowMyLocationButtonVisibility()
            return
        }
        suppressStandaloneAutoZoom = false
        showMyLocationEnabled = true
        gpsLocationLockActive = false
        applyStandaloneLocationStyle()
        stopStandaloneLocationUpdates(clearGpsFix = false)
        // Always wait for a fresh live callback fix after enabling location mode.
        // This guarantees the button shows a spinner and auto-zooms exactly once when fix arrives.
        waitingForStandaloneFix = true
        pendingAutoZoomToStandaloneFix = true
        startStandaloneLocationUpdates()
        updateShowMyLocationButtonVisibility()
    }

    private fun onShowMyLocationLongClick() {
        if (!showMyLocationEnabled) return
        showMyLocationEnabled = false
        gpsLocationLockActive = false
        suppressStandaloneAutoZoom = false
        waitingForStandaloneFix = false
        pendingAutoZoomToStandaloneFix = false
        lastStandaloneLocation = null
        disableLiveActiveFitForManualCameraInteraction()
        stopStandaloneLocationUpdates(clearGpsFix = true)
        restoreTrackerLocationStyle()
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
        if (resolveMyLocationPolicy().myLocationModeActive) {
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
        if (trackingRuntimeSnapshot().isRunning) return
        val navHost = navHost() ?: return
        if (!navHost.hasLocationPermission()) return
        if (fusedLocationClient == null) {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireContext())
        }
        val client = fusedLocationClient ?: return
        if (standaloneLocationCallback != null) return
        val myLocationModeActive = resolveMyLocationPolicy().myLocationModeActive
        val intervalMs = if (myLocationModeActive) 3000L else 10_000L
        val request = LocationRequest.Builder(
            if (myLocationModeActive) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_LOW_POWER,
            intervalMs
        ).apply {
            if (myLocationModeActive) {
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
                if (resolveMyLocationPolicy().shouldEnablePuck) {
                    waitingForStandaloneFix = false
                    maplibreMap?.let { map ->
                        LocationComponentHelper.forceLocation(map, location)
                    }
                    consumePendingStandaloneAutoZoom(location, animate = true)
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
                if (resolveMyLocationPolicy().shouldEnablePuck) {
                    if (isFreshStandaloneFix(location)) {
                        waitingForStandaloneFix = false
                        maplibreMap?.let { map ->
                            LocationComponentHelper.forceLocation(map, location)
                        }
                        consumePendingStandaloneAutoZoom(location, animate = true)
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
            gpsLocationLockActive = false
            lastStandaloneLocation = null
            waitingForStandaloneFix = false
            pendingAutoZoomToStandaloneFix = false
            suppressStandaloneAutoZoom = false
        }
        updateShowMyLocationButtonVisibility()
    }

    private fun updateTrackerLabel() {
        val trackingRunning = trackingRuntimeSnapshot().isRunning
        val selectedTrackerName = SelectedTrackerPrefs.selectedTrackerName(requireContext())
        if (!isLiveActiveFitAvailable() && liveActiveFitEnabled) {
            liveActiveFitEnabled = false
            updateShowMyLocationButtonVisibility()
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
        if (isSelectedDefaultTrackerMode()) {
            resetToTrackerButton.visibility = View.GONE
        }
        // Keep right-side controls in sync immediately when tracker context changes.
        updateShowMyLocationButtonVisibility()
    }

    /** Return true if we are currently viewing an active single-tracker stream. */
    fun isShowingStreamedTrack(): Boolean {
        if (!isAdded) return false
        return isStreaming()
    }

    /** True when a single tracker is currently active for live updates. */
    private fun isStreaming(): Boolean {
        return mapFlowViewModel.isStreaming(
            trackingRunning = trackingRuntimeSnapshot().isRunning,
            showAllTrackers = showAllTrackers,
            mapViewContext = mapViewContext,
            displayedTrackerId = displayedTrackerId
        )
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
        return tracker?.lastUpdateMs()
    }

    /** Show bottom-right indicator: red circle when streaming, spinner when loading geometry. */
    private fun updateBottomRightSpinner() {
        val showGpsAccuracyWarning = shouldShowGpsAccuracyWarning()
        val streaming = isStreaming()
        val loading = geometryLoadingInProgress
        val isSelectedDefaultTracker = isSelectedDefaultTrackerMode()
        val showSpinner = loading && streaming && !isSelectedDefaultTracker
        when {
            showSpinner -> {
                setGpsAccuracyWarningVisible(false)
                geometryLoadingSpinner.show()
                streamingIndicator.visibility = View.GONE
                bottomRightIndicatorContainer.visibility = View.VISIBLE
            }
            showGpsAccuracyWarning -> {
                geometryLoadingSpinner.hide()
                streamingIndicator.visibility = View.GONE
                setGpsAccuracyWarningVisible(true)
                bottomRightIndicatorContainer.visibility = View.VISIBLE
            }
            streaming -> {
                setGpsAccuracyWarningVisible(false)
                geometryLoadingSpinner.hide()
                streamingIndicator.visibility = View.VISIBLE
                bottomRightIndicatorContainer.visibility = View.VISIBLE
            }
            else -> {
                setGpsAccuracyWarningVisible(false)
                geometryLoadingSpinner.hide()
                streamingIndicator.visibility = View.GONE
                bottomRightIndicatorContainer.visibility = View.GONE
            }
        }
    }

    private fun shouldShowGpsAccuracyWarning(): Boolean {
        val runtime = trackingRuntimeSnapshot()
        if (!runtime.isRunning) return false
        val accuracyMeters = runtime.lastAccuracyMeters
        val thresholdMeters = trackingAccuracyThresholdMeters()
        return accuracyMeters == null || accuracyMeters > thresholdMeters
    }

    private fun trackingAccuracyThresholdMeters(): Float {
        return settingsRepository.getSettings().accuracyFilterMeters
    }

    private fun setGpsAccuracyWarningVisible(visible: Boolean) {
        if (visible) {
            gpsAccuracyWarningIndicator.visibility = View.VISIBLE
            if (!gpsWarningAnimationActive) {
                gpsAccuracyWarningIndicator.startAnimation(
                    AnimationUtils.loadAnimation(requireContext(), R.anim.gps_warning_flash)
                )
                gpsWarningAnimationActive = true
            }
            return
        }
        gpsAccuracyWarningIndicator.visibility = View.GONE
        gpsAccuracyWarningIndicator.clearAnimation()
        gpsWarningAnimationActive = false
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
     * BOUNDS_FIT updates are applied directly to the map so MapLibre uses the padding
     * embedded in the bounds update; going through moveCameraWithPadding would overwrite
     * that padding and can produce a wrong visible extent.
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
        intent?.let { activeCameraIntent = it }
        if (intent == CameraIntent.BOUNDS_FIT) {
            val targetPos = update.getCameraPosition(map)
            // MapLibre Transform skips move when new position equals current (isValidCameraPosition).
            // If they're equal the map never updates; nudge then re-apply so the move runs.
            if (targetPos != null && targetPos.equals(map.cameraPosition)) {
                val nudgeZoom = (targetPos.zoom - 0.01).coerceAtLeast(MapConstants.MIN_ZOOM)
                map.moveCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder(targetPos).zoom(nudgeZoom).build()
                    ),
                    null
                )
                view?.post {
                    if (!isAdded) return@post
                    if (animate) {
                        map.animateCamera(update, durationMs, callback)
                    } else {
                        map.moveCamera(update, callback)
                    }
                }
                return
            }
            if (animate) {
                map.animateCamera(update, durationMs, callback)
            } else {
                map.moveCamera(update, callback)
            }
            return
        }
        val overlayPaddingForMove = resolveOverlayPaddingForIntent(map, intent)
        MapZoomOrchestrator.applyUnifiedCameraMove(
            mapManager = mapManager,
            map = map,
            update = update,
            paddingMode = paddingMode,
            followLockPadding = MapConstants.FOLLOW_LOCK_PADDING,
            overlayAwarePadding = overlayPaddingForMove,
            intent = intent,
            onIntent = { activeCameraIntent = it },
            animate = animate,
            durationMs = durationMs,
            callback = callback
        )
    }

    private fun resolveOverlayPaddingForIntent(map: MapLibreMap, intent: CameraIntent?): DoubleArray {
        if (intent != CameraIntent.BOUNDS_FIT) {
            return getMapPaddingArray()
        }
        val rawInsets = getMapPaddingArray()
        val rawPaddingPx = intArrayOf(
            rawInsets[0].toInt(),
            rawInsets[1].toInt(),
            rawInsets[2].toInt(),
            rawInsets[3].toInt()
        )
        val sanitizedPx = MapZoomOrchestrator.sanitizeBoundsFitPaddingPx(
            map = map,
            rawPaddingPx = rawPaddingPx,
            minViewportWidthFraction = MapConstants.MIN_BOUNDS_FIT_VIEWPORT_WIDTH_FRACTION,
            minViewportHeightFraction = MapConstants.MIN_BOUNDS_FIT_VIEWPORT_HEIGHT_FRACTION
        )
        return doubleArrayOf(
            sanitizedPx[0].toDouble(),
            sanitizedPx[1].toDouble(),
            sanitizedPx[2].toDouble(),
            sanitizedPx[3].toDouble()
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
                liveActiveFitButton
            ),
            bottomRightIndicatorContainer = bottomRightIndicatorContainer,
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

    private fun resetSingleTrackerContext(stopStreaming: Boolean) {
        liveActiveFitEnabled = false
        if (stopStreaming) {
            stopLiveTrackStreaming()
        }
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
        followLockEnabled = false
        updateFollowLockButton()
    }

    private fun clearSingleTrackerDataAndRender() {
        trackPoints.clear()
        trackTimestamps.clear()
        updateTrackLine()
    }

    private fun setDisplayedTrackerPlaceholder(trackerId: String, trackerName: String?) {
        displayedTracker = null
        displayedTrackerId = trackerId
        displayedTrackerName = trackerName?.ifEmpty { null }
        displayedTrackerIsOwner = true
        displayedGroupName = null
        mapViewContext = MapViewContext.SINGLE_TRACKER
        lastCachedUpdateTimeMs = null
        currentTrackerColor = null
        lastStreamedAccuracyMeters = null
    }

    /**
     * Clear the map track and refetch only the currently selected tracker.
     * Call this when switching to the map from "View on map" so only that tracker is shown.
     * If the list provided an initial track (latest 100 points), shows it immediately then loads full geometry in background.
     */
    fun refreshTrackForSelectedTracker() {
        bumpTrackerRequestEpoch()
        resetSingleTrackerContext(stopStreaming = false)

        val initial = navHost()?.getAndClearInitialTrackForMap()
        val loadTrackerId = initial?.id ?: SelectedTrackerPrefs.selectedTrackerId(requireContext())
        mapViewContext = MapViewContext.SINGLE_TRACKER
        pendingDisplayedTrackerIdOverride = loadTrackerId.takeIf { it.isNotEmpty() }

        val isSwitching = displayedTrackerId != null && displayedTrackerId != loadTrackerId

        // Immediate visual clear: hide annotation layers so old data doesn't flash.
        if (isSwitching) {
            setAnnotationLayersVisibility(false)
            mapFragment?.mapViewOrNull?.alpha = 0f
            mapFragment?.mapViewOrNull?.animate()?.alpha(1f)?.setDuration(200)?.setStartDelay(50)?.start()
        }

        clearSingleTrackerDataAndRender()
        zoomToTrackAfterLoad = true

        if (loadTrackerId.isEmpty()) {
            displayedTracker = null
            displayedTrackerId = null
            displayedTrackerName = null
            stopLiveTrackStreaming()
            updateZoomToLatestButtonState()
            updateTrackerLabel()
            return
        }
        if (initial != null) {
            primeDisplayedTrackerFromInitial(initial)
        } else {
            setDisplayedTrackerPlaceholder(loadTrackerId, SelectedTrackerPrefs.selectedTrackerName(requireContext()))
        }
        // Keep fragment-local state and ViewModel state aligned immediately so uiState collection
        // does not overwrite the freshly requested tracker with stale displayedTrackerId.
        mapFlowViewModel.updateUiState {
            it.copy(
                displayedTrackerId = displayedTrackerId,
                displayedTrackerName = displayedTrackerName,
                displayedGroupName = null,
                showAllTrackers = false,
                mode = MapScreenMode.Single
            )
        }
        if (isSwitching) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (isAdded) {
                    mapFlowViewModel.handleIntent(MapIntent.LoadSingleTracker(loadTrackerId, forceReplace = false))
                }
            }, 50)
        } else {
            mapFlowViewModel.handleIntent(MapIntent.LoadSingleTracker(loadTrackerId, forceReplace = false))
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
        if (gpsLocationLockActive) {
            gpsLocationLockActive = false
            maplibreMap?.let { LocationComponentHelper.setCameraTracking(it, enabled = false) }
        }
        pendingAutoZoomToStandaloneFix = false
        liveActiveFitEnabled = false
        updateShowMyLocationButtonVisibility()
        clearMultiTrackContextState()
        clearMapSelection()
        clearAllTrackSources()
        setAllTrackLayersVisibility(true)
        setAnnotationLayersVisibility(false)
        currentGroupForMap = group
        mapFlowViewModel.handleIntent(MapIntent.LoadGroup(group, zoomToTrackerId))
    }

    fun showAllTrackersFromSettings() {
        if (!isAdded) {
            pendingShowAllTrackers = true
            showAllTrackers = true
            mapViewContext = MapViewContext.SINGLE_TRACKER
            return
        }
        loadAllTrackersAndApply()
    }

    private fun loadAllTrackersAndApply() {
        bumpTrackerRequestEpoch()
        liveActiveFitEnabled = false
        if (gpsLocationLockActive) {
            gpsLocationLockActive = false
            maplibreMap?.let { LocationComponentHelper.setCameraTracking(it, enabled = false) }
        }
        pendingAutoZoomToStandaloneFix = false
        updateShowMyLocationButtonVisibility()
        clearMultiTrackContextState()
        activeCameraIntent = CameraIntent.BOUNDS_FIT
        preserveCenteredAllTrackersFit = false
        if (maplibreMap == null || maplibreMap?.style == null) {
            pendingShowAllTrackers = true
            showAllTrackers = true
            mapViewContext = MapViewContext.SINGLE_TRACKER
            updateTrackerLabel()
            return
        }
        mapFlowViewModel.handleIntent(MapIntent.LoadAllTrackers)
    }

    /**
     * Rehydrate all-trackers sources after style reload when we have local tail cache.
     * Returns false when cache is too thin, so caller can fall back to network reload.
     */
    private fun restoreAllTrackersFromCacheIfAvailable(map: MapLibreMap, style: Style): Boolean {
        val state = allTrackersState
        val trackers = state?.trackers ?: return false
        val cachedCoordsById: Map<String, List<List<Double>>>? = when {
            !state.normalizedCoordsById.isNullOrEmpty() -> state.normalizedCoordsById
            multiTrackCoordsCache.isNotEmpty() -> multiTrackCoordsCache.mapValues { it.value.toList() }
            else -> null
        }
        val hasTailData = (cachedCoordsById?.values?.any { it.size >= 2 } == true) ||
            trackers.any { (it.geometry?.coordinates?.size ?: 0) >= 2 }
        if (!hasTailData) return false
        applyAllTrackersToMap(
            trackers = trackers,
            coordsById = cachedCoordsById ?: emptyMap(),
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
        allTrackersState = AllTrackersMapState(
            trackers = trackers,
            normalizedCoordsById = renderData.normalizedCoordsById.mapValues { it.value.toList() }
        )
        val sourcesUpdated = MapLayerVisibility.updateAllTrackSources(
            style = style,
            lineFeatures = renderData.lineFeatures,
            pointFeatures = renderData.pointFeatures,
            logTag = TAG
        )
        if (!sourcesUpdated) return
        setAllTrackLayersVisibility(true)
        setAnnotationLayersVisibility(false)
        updateTrackerLabel()
        updateZoomToLatestButtonState()

        if (fitBounds) {
            var fitTrackers = trackers
            var fitCoordsByTrackerId: Map<String, List<LatLng>> = renderData.coordsByTrackerId
            /** One point per tracker (last position); used for best-effort selection. */
            fun representativePoints(coordsByTrackerId: Map<String, List<LatLng>>): List<LatLng> {
                return coordsByTrackerId.values.mapNotNull { coords -> coords.lastOrNull() }
            }
            var boundsCoords = if (fitToTrackerId != null) {
                renderData.coordsByTrackerId[fitToTrackerId] ?: emptyList()
            } else {
                representativePoints(fitCoordsByTrackerId)
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
                    boundsCoords = representativePoints(fitCoordsByTrackerId)
                }
            }
            if (boundsCoords.isNotEmpty()) {
                if (boundsCoords.size >= 2) {
                    val boundsBuilder = LatLngBounds.Builder()
                    boundsCoords.forEach { boundsBuilder.include(it) }
                    val bounds = boundsBuilder.build()
                    // Defer fit until map view has valid size; post on MapView then short delay.
                    val mapView = mapFragment?.mapViewOrNull ?: view
                    mapView?.postDelayed({
                        if (!isAdded || !showAllTrackers || map.style == null) return@postDelayed
                        moveCameraForAllTrackersWithMinZoom(
                            map, bounds, fitCoordsByTrackerId, fitTrackers, fitToTrackerId
                        )
                    }, 150L)
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
        updateShowMyLocationButtonVisibility()
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
        updateShowMyLocationButtonVisibility()
    }

    private fun disableLiveActiveFitForManualCameraInteraction() {
        if (!liveActiveFitEnabled) return
        liveActiveFitEnabled = false
        liveStreamCoordinator.cancelSingleLiveFit()
        if (activeCameraIntent == CameraIntent.SINGLE_TRACKER_FOCUS) {
            activeCameraIntent = CameraIntent.NONE
        }
        updateLiveActiveFitButtonUi()
        updateShowMyLocationButtonVisibility()
        // Restore default overlay-aware padding state without forcing a new camera move mid-gesture.
        refreshMapPaddingForCurrentMode(force = true, allowCameraMove = false)
    }

    private fun updateLiveActiveFitButtonUi() {
        val trackingRunning = trackingRuntimeSnapshot().isRunning
        val visible = !trackingRunning &&
            isLiveActiveFitAvailable() &&
            !isSelectedDefaultTrackerMode()
        val enabled = isLiveActiveFitToggleEnabled()
        if ((!enabled || trackingRunning) && liveActiveFitEnabled) {
            liveActiveFitEnabled = false
        }
        liveActiveFitButton.visibility = if (visible) View.VISIBLE else View.GONE
        liveActiveFitButton.isEnabled = enabled
        liveActiveFitButton.alpha = 1f
        val primaryBlue = ContextCompat.getColor(requireContext(), R.color.primary_blue)
        val disabledBlue = Color.rgb(
            (Color.red(primaryBlue) * 0.6f + 255f * 0.4f).toInt(),
            (Color.green(primaryBlue) * 0.6f + 255f * 0.4f).toInt(),
            (Color.blue(primaryBlue) * 0.6f + 255f * 0.4f).toInt()
        )
        liveActiveFitButton.setCardBackgroundColor(
            if (enabled) primaryBlue
            else disabledBlue
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
        val state = allTrackersState ?: return
        val map = maplibreMap ?: return
        val style = map.style ?: return
        applyAllTrackersToMap(
            trackers = state.trackers,
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
        if (points.size == 1) {
            applyUnifiedCameraMove(
                map = map,
                update = CameraUpdateFactory.newLatLngZoom(points.single(), MapConstants.TRACKER_CARD_FOCUS_ZOOM),
                paddingMode = CameraPaddingMode.CENTERED,
                intent = CameraIntent.SINGLE_TRACKER_FOCUS
            )
            return
        }
        val boundsBuilder = LatLngBounds.Builder()
        points.forEach { boundsBuilder.include(it) }
        val bounds = boundsBuilder.build()
        val rawPaddingPx = getBoundsPaddingEdgesPx(0)
        val fitPaddingPx = MapZoomOrchestrator.sanitizeBoundsFitPaddingPx(
            map = map,
            rawPaddingPx = rawPaddingPx,
            minViewportWidthFraction = MapConstants.MIN_BOUNDS_FIT_VIEWPORT_WIDTH_FRACTION,
            minViewportHeightFraction = MapConstants.MIN_BOUNDS_FIT_VIEWPORT_HEIGHT_FRACTION
        )
        val boundsUpdate = CameraUpdateFactory.newLatLngBounds(bounds, fitPaddingPx[0], fitPaddingPx[1], fitPaddingPx[2], fitPaddingPx[3])
        val fittedPosition = boundsUpdate.getCameraPosition(map)
        val targetZoom = fittedPosition?.zoom?.toDouble()?.coerceAtLeast(MapConstants.MIN_ZOOM) ?: map.cameraPosition.zoom
        val visibleW = (map.width - fitPaddingPx[0] - fitPaddingPx[2]).coerceAtLeast(1f).toDouble()
        val visibleH = (map.height - fitPaddingPx[1] - fitPaddingPx[3]).coerceAtLeast(1f).toDouble()
        val best = MapBoundsFitController.selectBestEffortAtZoom(
            representativeTrackerPoints = points.mapIndexed { idx, latLng -> "live-$idx" to latLng },
            boundsCenter = bounds.center,
            zoom = targetZoom,
            visibleWidthPx = visibleW,
            visibleHeightPx = visibleH
        )
        val moveUpdate = if (best != null) {
            val worldSize = MapCameraMath.worldSizeAtZoom(targetZoom)
            val targetCenter = LatLng(
                MapCameraMath.worldYToLatDeg(best.centerY, worldSize),
                MapCameraMath.worldXToLonDeg(best.centerX, worldSize)
            )
            CameraUpdateFactory.newLatLngZoom(targetCenter, targetZoom)
        } else {
            boundsUpdate
        }
        applyUnifiedCameraMove(
            map = map,
            update = moveUpdate,
            paddingMode = CameraPaddingMode.OVERLAY_AWARE,
            intent = CameraIntent.BOUNDS_FIT
        )
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
        )
    }

    private fun selectedMapTrackerFromFeature(feature: Feature): SelectedMapTracker? {
        val defaultHexColor = defaultTrackerColorHex(requireContext()).let { if (it.startsWith("#")) it else "#$it" }
        return MapSelectionUtils.selectedFromFeature(
            feature = feature,
            defaultHexColor = defaultHexColor,
            lastKnownById = lastKnownUpdateTimeMsByTrackerId,
            resolveTrackerIsOwner = { _ -> false }
        )
    }

    /** Some payloads may omit is_owner; rely only on the payload provided to the map layer. */
    private fun resolveTrackerIsOwner(tracker: Tracker?): Boolean {
        return tracker?.isOwner() == true
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
        val state = allTrackersState ?: return
        val trackers = state.trackers
        val coordsById = state.normalizedCoordsById
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
            announceSelectionForAccessibility(getString(R.string.selected_tracker, sel.name))
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
        mapTrackerInfoZoomLock.visibility = View.VISIBLE
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
            navHost()?.showTrackerParamsFragment(
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
                navHost()?.openGroupMembersAndScrollTo(currentGroupForMap!!, sel.id)
            sel.isOwner ->
                navHost()?.openTrackersAndScrollTo(sel.id)
            else ->
                navHost()?.openSharedAndScrollTo(sel.id)
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
            { payload, _ -> resolveTrackerIsOwner(payload) },
            ::trackerLastUpdateMs
        )
    }

    /** @param chevronOnly when true (all-track mode), use only the chevron icon without the white circle. */
    private fun ensureArrowImageInStyle(style: Style, hexColor: String, chevronOnly: Boolean = false) {
        MapPointFeatureHelper.ensureArrowImageInStyle(requireContext(), style, hexColor, chevronOnly)
    }

    private fun applyDisplayedTrackerMetadata(tracker: Tracker) {
        lastCachedUpdateTimeMs = trackerLastUpdateMs(tracker)
        currentTrackerColor = (tracker.color ?: defaultTrackerColorHex(requireContext())).let {
            if (it.startsWith("#")) it else "#$it"
        }
        displayedTracker = tracker
        displayedTrackerId = tracker.id
        displayedTrackerName = tracker.name
        displayedTrackerIsOwner = tracker.isOwner()
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
            resolveTrackerIsOwner(tracker)
        }
        lastCachedUpdateTimeMs = trackerLastUpdateMs(tracker)
        displayedGroupName = null
        mapViewContext = MapViewContext.SINGLE_TRACKER
        if (pendingDisplayedTrackerIdOverride == trackerId) {
            pendingDisplayedTrackerIdOverride = null
        }
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
            scheduleTrackLineUpdate()
            setAnnotationLayersVisibility(true)
            val map = maplibreMap
            val allowTrackerCameraMoveInMyLocation =
                activeCameraIntent == CameraIntent.SINGLE_TRACKER_FOCUS ||
                    activeCameraIntent == CameraIntent.GROUP_MEMBER_FOCUS
            var zoomApplied = false
            if (map != null && trackPoints.isNotEmpty() &&
                (!showMyLocationEnabled || allowTrackerCameraMoveInMyLocation)
            ) {
                zoomToLatestTrackPoint(map, tracker.last_point)
                zoomApplied = true
            }
            if (zoomApplied) {
                zoomToTrackAfterLoad = false
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
        updateZoomToLatestButtonState()
        updateTrackerLabel()
        startLiveTrackStreamingForDisplayedTracker()
    }

    private fun primeDisplayedTrackerFromInitial(initial: Tracker) {
        lastStreamedPointTimeMs = null
        applyDisplayedTrackerMetadata(initial)
        displayedGroupName = null
        mapViewContext = MapViewContext.SINGLE_TRACKER
        lastStreamedAccuracyMeters = (initial.point_params?.lastOrNull()?.get("acc") as? Number)
            ?.toFloat()
            ?.takeIf { it > 0f }
    }

    /**
     * Refetch and redraw the selected tracker's track without moving the camera.
     */
    fun restoreTrackForSelectedTracker() {
        bumpTrackerRequestEpoch()
        resetSingleTrackerContext(stopStreaming = true)
        clearSingleTrackerDataAndRender()
        val selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(requireContext())
        val selectedTrackerName = SelectedTrackerPrefs.selectedTrackerName(requireContext())
        if (selectedTrackerId.isEmpty()) {
            updateZoomToLatestButtonState()
            updateTrackerLabel()
            return
        }
        setDisplayedTrackerPlaceholder(selectedTrackerId, selectedTrackerName)
        updateTrackerLabel()
        mapFlowViewModel.handleIntent(MapIntent.LoadSingleTracker(trackerId = selectedTrackerId, forceReplace = true))
    }

    private fun fetchHistory() {
        liveActiveFitEnabled = false
        updateShowMyLocationButtonVisibility()
        if (mapViewContext == MapViewContext.GROUP) {
            updateZoomToLatestButtonState()
            updateTrackerLabel()
            return
        }
        mapFlowViewModel.handleIntent(MapIntent.LoadSingleTracker(trackerId = displayedTrackerId, forceReplace = false))
    }

    private fun fetchFullGeometryAndApply(trackerId: String, forceReplace: Boolean = false) {
        mapFlowViewModel.handleIntent(MapIntent.LoadSingleTracker(trackerId = trackerId, forceReplace = forceReplace))
    }

    /** Start live streaming for the currently displayed single tracker. */
    private fun startLiveTrackStreamingForDisplayedTracker() {
        mapFlowViewModel.startLiveTrackStreamingForDisplayedTracker(
            displayedTrackerId = displayedTrackerId,
            displayedTrackerName = displayedTrackerName,
            selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(requireContext()),
            mapViewContext = mapViewContext
        )
    }

    /** Start live streaming for a set of trackers (group/all-trackers context). */
    private fun startLiveTrackStreamingForTrackerSet(trackerIds: Set<String>, trackerName: String? = null) {
        mapFlowViewModel.startLiveTrackStreamingForTrackerSet(
            trackerIds = trackerIds,
            trackerName = trackerName
        )
    }

    private fun stopLiveTrackStreaming() {
        mapFlowViewModel.stopLiveTrackStreaming()
        // Refresh spinner/label state immediately after clearing active stream ids.
        updateStreamingUi()
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
            style = style,
            trackPoints = trackPoints,
            currentTrackerColor = currentTrackerColor,
            lastStreamedAccuracyMeters = lastStreamedAccuracyMeters,
            trackingServiceAccuracyMeters = trackingRuntimeSnapshot().lastAccuracyMeters,
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

    private fun observeMapFlow() {
        mapCommandsJob?.cancel()
        mapCommandsJob = viewLifecycleOwner.lifecycleScope.launch {
            launch {
                mapFlowViewModel.uiState.collect { state ->
                    val nextMapViewContext = when (state.mode) {
                        is MapScreenMode.GroupMode -> MapViewContext.GROUP
                        else -> MapViewContext.SINGLE_TRACKER
                    }
                    val pendingOverride = pendingDisplayedTrackerIdOverride
                    val shouldDeferTrackerContextUpdate = pendingOverride != null &&
                        (
                            nextMapViewContext != MapViewContext.SINGLE_TRACKER ||
                                state.displayedTrackerId != pendingOverride
                            )

                    if (shouldDeferTrackerContextUpdate) {
                    } else {
                        if (pendingOverride != null && state.displayedTrackerId == pendingOverride) {
                            pendingDisplayedTrackerIdOverride = null
                        }
                        showAllTrackers = state.showAllTrackers
                        displayedTrackerId = state.displayedTrackerId
                        displayedTrackerName = state.displayedTrackerName
                        displayedGroupName = state.displayedGroupName
                        mapViewContext = nextMapViewContext
                    }
                    activeStreamedTrackerIds = state.activeStreamedTrackerIds
                    if (state.loading) {
                        mapLoadingOverlay.visibility = View.VISIBLE
                        mapLoadingSpinner.start()
                    } else {
                        mapLoadingOverlay.visibility = View.GONE
                        mapLoadingSpinner.stop()
                    }
                    updateTrackerLabel()
                }
            }
            launch {
                mapFlowViewModel.commands.collect { command ->
                    when (command) {
                        is MapCommand.RenderSingleTracker -> {
                            val trackerWithGeometry = if (command.snapshot.tracker.geometry == null && command.snapshot.coordinates.isNotEmpty()) {
                                command.snapshot.tracker.copy(
                                    geometry = GeoJsonLineString(
                                        type = "LineString",
                                        coordinates = command.snapshot.coordinates
                                    )
                                )
                            } else {
                                command.snapshot.tracker
                            }
                            handleSingleTrackGeometryLoaded(
                                tracker = trackerWithGeometry,
                                trackerId = trackerWithGeometry.id,
                                forceReplace = command.snapshot.forceReplace
                            )
                        }
                        is MapCommand.RenderAllTrackers -> {
                            val map = maplibreMap
                            val style = map?.style
                            if (map != null && style != null) {
                                applyAllTrackersToMap(
                                    trackers = command.snapshot.trackers,
                                    coordsById = command.snapshot.coordsByTrackerId,
                                    map = map,
                                    style = style,
                                    fitBounds = command.snapshot.fitBounds,
                                    fitToTrackerId = command.snapshot.fitToTrackerId,
                                    liveActiveOnlyFit = command.snapshot.liveActiveOnlyFit
                                )
                            } else {
                                pendingShowAllTrackers = true
                            }
                        }
                        is MapCommand.ApplyTrackPoint -> {
                            command.event.accuracyMeters?.let { lastStreamedAccuracyMeters = it }
                            applyLiveStreamPoint(
                                trackId = command.event.trackId,
                                lat = command.event.lat,
                                lon = command.event.lon,
                                timestampMs = command.event.timestampMs.takeIf { it > 0L } ?: System.currentTimeMillis()
                            )
                        }
                        is MapCommand.ApplyCameraPolicy -> {
                            followLockEnabled = command.command.followLockEnabled
                            updateFollowLockButton()
                        }
                        is MapCommand.ShowError -> {
                            navHost()?.showSnackbar(command.message)
                        }
                    }
                }
            }
            launch {
                TrackingRuntimeStateStore.state.collect {
                    if (isAdded) {
                        updateBottomRightSpinner()
                    }
                }
            }
        }
    }

    private fun announceSelectionForAccessibility(message: String) {
        val accessibilityManager = context?.getSystemService(AccessibilityManager::class.java) ?: return
        if (!accessibilityManager.isEnabled) return
        val root = view ?: return
        root.contentDescription = message
        root.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    private fun trackingRuntimeSnapshot() = TrackingRuntimeStateStore.state.value

    companion object {
        private const val TAG = "MapFragment"
    }

}
