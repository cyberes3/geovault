package com.geovault.tracker.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.geovault.common.LoadingSpinner
import com.geovault.tracker.R
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerRepository
import com.geovault.tracker.data.TrackerDetailRepository
import com.geovault.tracker.data.TrackerRepositoryTrackerDetailRepository
import com.geovault.tracker.lastPosition
import com.geovault.tracker.lastUpdateMs
import com.geovault.tracker.pipeline.TrackPointBusGateway
import com.geovault.tracker.pipeline.TrackPointEventStream
import com.geovault.tracker.services.TrackingRuntimeStateStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TrackerParamsFragment : Fragment() {
    private val trackPointEvents: TrackPointEventStream = TrackPointBusGateway
    private val trackerDetailRepository: TrackerDetailRepository = TrackerRepositoryTrackerDetailRepository()

    private lateinit var paramsName: TextView
    private lateinit var paramsLastUpdate: TextView
    private lateinit var paramsPositionCard: View
    private lateinit var paramsPosition: TextView
    private lateinit var paramsGrid: RecyclerView
    private lateinit var paramsWaitingCard: View
    private lateinit var paramsWaitingMessage: TextView
    private lateinit var paramsLoadingOverlay: View
    private lateinit var paramsLoadingSpinner: LoadingSpinner
    private lateinit var closeButton: ImageButton
    private lateinit var paramsSwipeRefresh: SwipeRefreshLayout
    private var trackerId: String? = null
    private var streamCollectionJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_tracker_params, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        paramsName = view.findViewById(R.id.paramsName)
        paramsLastUpdate = view.findViewById(R.id.paramsLastUpdate)
        paramsPositionCard = view.findViewById(R.id.paramsPositionCard)
        paramsPosition = view.findViewById(R.id.paramsPosition)
        paramsGrid = view.findViewById(R.id.paramsGrid)
        paramsWaitingCard = view.findViewById(R.id.paramsWaitingCard)
        paramsWaitingMessage = view.findViewById(R.id.paramsWaitingMessage)
        paramsLoadingOverlay = view.findViewById(R.id.paramsLoadingOverlay)
        paramsLoadingSpinner = view.findViewById(R.id.paramsLoadingSpinner)
        closeButton = view.findViewById(R.id.paramsCloseButton)
        paramsSwipeRefresh = view.findViewById(R.id.paramsSwipeRefresh)

        paramsSwipeRefresh.setColorSchemeResources(R.color.primary_blue)
        paramsSwipeRefresh.setOnRefreshListener { loadTrackerData(refresh = true) }

        closeButton.setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }

        // Show name, last update, and position immediately if passed (e.g. from trackers list)
        val args = arguments
        trackerId = args?.getString(ARG_TRACKER_ID) ?: return
        args.getString(ARG_TRACKER_NAME)?.let { name ->
            if (name.isNotBlank()) {
                paramsName.visibility = View.VISIBLE
                paramsName.text = name.uppercase(Locale.getDefault())
            } else {
                paramsName.visibility = View.INVISIBLE
            }
        }
        // args is non-null here (we would have returned above if trackerId was null)
        val lastUpdateMs = args.getLong(ARG_LAST_UPDATE_MS, -1L)
        paramsLastUpdate.text = if (lastUpdateMs >= 0) formatTimeLocal(lastUpdateMs) else getString(R.string.no_points_yet)
        val lat = args.getDouble(ARG_POSITION_LAT, Double.NaN)
        val lon = args.getDouble(ARG_POSITION_LON, Double.NaN)
        paramsPosition.text = if (!lat.isNaN() && !lon.isNaN()) formatLatLon(lat, lon) else "-"

        loadTrackerData(refresh = false)
    }

    override fun onStart() {
        super.onStart()
        if (streamCollectionJob?.isActive == true) return
        streamCollectionJob = viewLifecycleOwner.lifecycleScope.launch {
            trackPointEvents.events.collect { event ->
                if (event.trackId != trackerId) return@collect
                if (!isAdded) return@collect
                updateFromStreamPoint(
                    lat = event.lat,
                    lon = event.lon,
                    timestampMs = event.timestampMs,
                    propsJson = event.propsJson
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        streamCollectionJob?.cancel()
        streamCollectionJob = null
    }

    private fun loadTrackerData(refresh: Boolean = false) {
        val id = trackerId ?: return
        val selectedId = SelectedTrackerPrefs.selectedTrackerId(requireContext())
        val runtime = TrackingRuntimeStateStore.state.value
        val isLocalTrackingMode = runtime.isRunning &&
            selectedId.isNotEmpty() &&
            id == selectedId

        if (refresh) {
            paramsSwipeRefresh.isRefreshing = true
        } else {
            // Keep the existing name, last update, and position visible
            // Only hide the params grid and show loading spinner
            paramsGrid.visibility = View.GONE
            paramsWaitingCard.visibility = View.GONE
            paramsLoadingOverlay.visibility = View.VISIBLE
            paramsLoadingSpinner.start()
        }

        // Selected track: fill from local cache (geometryCache from map, etc.) when available.
        if (selectedId.isNotEmpty() && id == selectedId) {
            // Don't clear cache so getTrackerGeometry can return cached geometry/params if available.
        } else {
            TrackerRepository.clearSelectedTrackerCaches()
        }

        if (isLocalTrackingMode) {
            if (isAdded) {
                requireActivity().runOnUiThread {
                    paramsLoadingSpinner.stop(hide = false)
                    paramsLoadingOverlay.visibility = View.GONE
                    paramsSwipeRefresh.isRefreshing = false
                    applyLatestLocalTrackingPoint()
                }
            }
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            // Important: only the single-tracker call drives the params UI. Use geometry endpoint for full track + params.
            val result = trackerDetailRepository.loadTrackerGeometry(requireContext(), id)
            if (isAdded) {
                requireActivity().runOnUiThread {
                    paramsLoadingSpinner.stop(hide = false)
                    paramsLoadingOverlay.visibility = View.GONE
                    paramsSwipeRefresh.isRefreshing = false
                    if (result is RepositoryResult.Success) {
                        bindTracker(result.data)
                    }
                }
            }

            // Fire-and-forget: refresh trackers list in background so list is up to date when user goes back.
            val ctx = context ?: return@launch
            trackerDetailRepository.refreshTrackers(ctx)
            if (!isAdded || activity == null) return@launch
            requireActivity().runOnUiThread {
                if (!isAdded || activity == null) return@runOnUiThread
                requireActivity().supportFragmentManager.setFragmentResult(
                    TrackersListFragment.REQUEST_UPDATE_LIST_FROM_CACHE,
                    Bundle()
                )
            }
        }
    }

    private fun applyLatestLocalTrackingPoint() {
        val runtime = TrackingRuntimeStateStore.state.value
        val lat = runtime.lastTrackedLatitude
        val lon = runtime.lastTrackedLongitude
        val tsMs = runtime.lastTrackedTimestampMs
        updateFromStreamPoint(
            lat = lat ?: Double.NaN,
            lon = lon ?: Double.NaN,
            timestampMs = tsMs,
            propsJson = runtime.lastTrackedPropsJson
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        paramsLoadingSpinner.stop()
        paramsSwipeRefresh.isRefreshing = false
    }

    /**
     * Update displayed params from a streamed point (same track as this fragment).
     * Called when we receive track point events for our trackerId so the params modal stays in sync.
     */
    private fun updateFromStreamPoint(lat: Double, lon: Double, timestampMs: Long, propsJson: String?) {
        if (!isAdded) return
        paramsLastUpdate.text = if (timestampMs > 0) formatTimeLocal(timestampMs) else getString(R.string.no_points_yet)
        paramsPositionCard.visibility = View.VISIBLE
        paramsPosition.text = if (!lat.isNaN() && !lon.isNaN()) formatLatLon(lat, lon) else "-"
        val propsMap = parsePropsJson(propsJson)
        if (propsMap.isNotEmpty()) {
            paramsGrid.visibility = View.VISIBLE
            paramsWaitingCard.visibility = View.GONE
            val entries = propsMap.entries.sortedBy { it.key }.map { TrackerParamEntry(it.key, it.value) }
            paramsGrid.layoutManager = GridLayoutManager(requireContext(), 2)
            paramsGrid.adapter = TrackerParamsAdapter(requireContext(), entries)
        } else {
            paramsGrid.visibility = View.GONE
            paramsWaitingCard.visibility = View.VISIBLE
            paramsWaitingMessage.text = getString(R.string.no_extended_params_latest_point)
        }
    }

    private fun parsePropsJson(json: String?): Map<String, Any?> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith { key -> obj.opt(key) }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun bindTracker(tracker: Tracker) {
        val lastTimestampMs = tracker.lastUpdateMs()
        val lastPosition = tracker.lastPosition()
        val pointParams = tracker.point_params.orEmpty()
        val latestPointParams = pointParams.lastOrNull() ?: emptyMap<String, Any?>()

        if (!tracker.name.isNullOrBlank()) {
            paramsName.visibility = View.VISIBLE
            paramsName.text = tracker.name.uppercase(Locale.getDefault())
        } else {
            paramsName.visibility = View.INVISIBLE
        }

        paramsLastUpdate.text = if (lastTimestampMs != null) {
            formatTimeLocal(lastTimestampMs)
        } else {
            getString(R.string.no_points_yet)
        }

        paramsPosition.text = if (lastPosition != null) {
            formatLatLon(lastPosition.first, lastPosition.second)
        } else {
            "-"
        }

        val hasStoredParams = latestPointParams.isNotEmpty()
        if (hasStoredParams) {
            paramsGrid.visibility = View.VISIBLE
            paramsWaitingCard.visibility = View.GONE
            val entries = latestPointParams.entries.sortedBy { it.key }.map { TrackerParamEntry(it.key, it.value) }
            paramsGrid.layoutManager = GridLayoutManager(requireContext(), 2)
            paramsGrid.adapter = TrackerParamsAdapter(requireContext(), entries)
        } else if (lastTimestampMs != null || lastPosition != null) {
            paramsGrid.visibility = View.GONE
            paramsWaitingCard.visibility = View.VISIBLE
            paramsWaitingMessage.text = getString(R.string.no_extended_params_latest_point)
        } else {
            paramsGrid.visibility = View.GONE
            paramsWaitingCard.visibility = View.VISIBLE
            paramsWaitingMessage.text = getString(R.string.waiting_for_data)
        }
    }

    private fun formatTimeLocal(ms: Long): String {
        val sdf = SimpleDateFormat("MMM d, yyyy, h:mm:ss a", Locale.getDefault())
        return sdf.format(Date(ms))
    }

    private fun formatLatLon(lat: Double, lon: Double): String {
        return "%.6f, %.6f".format(Locale.US, lat, lon)
    }

    companion object {
        const val ARG_TRACKER_ID = "tracker_id"
        const val ARG_TRACKER_NAME = "tracker_name"
        const val ARG_LAST_UPDATE_MS = "tracker_last_update_ms"
        const val ARG_POSITION_LAT = "tracker_position_lat"
        const val ARG_POSITION_LON = "tracker_position_lon"
    }
}
