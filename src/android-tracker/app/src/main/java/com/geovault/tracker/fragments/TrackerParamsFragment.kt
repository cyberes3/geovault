package com.geovault.tracker.fragments

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.fragment.app.Fragment
import com.geovault.common.LoadingSpinner
import com.geovault.tracker.LiveTrackStreamingService
import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerRepository
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TrackerParamsFragment : Fragment() {

    private lateinit var paramsName: TextView
    private lateinit var paramsLastUpdate: TextView
    private lateinit var paramsPositionCard: View
    private lateinit var paramsPosition: TextView
    private lateinit var paramsGrid: RecyclerView
    private lateinit var paramsWaitingCard: View
    private lateinit var paramsLoadingOverlay: View
    private lateinit var paramsLoadingSpinner: LoadingSpinner
    private lateinit var closeButton: ImageButton
    private var trackerId: String? = null

    private val streamPointReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != LiveTrackStreamingService.BROADCAST_TRACK_POINT) return
            val trackId = intent.getStringExtra(LiveTrackStreamingService.EXTRA_TRACK_ID) ?: return
            if (trackId != this@TrackerParamsFragment.trackerId) return
            if (!isAdded) return
            val lat = intent.getDoubleExtra(LiveTrackStreamingService.EXTRA_POINT_LAT, Double.NaN)
            val lon = intent.getDoubleExtra(LiveTrackStreamingService.EXTRA_POINT_LON, Double.NaN)
            val tsMs = intent.getLongExtra(LiveTrackStreamingService.EXTRA_POINT_TS_MS, 0L)
            val propsJson = intent.getStringExtra(LiveTrackStreamingService.EXTRA_PROPS_JSON)
            requireActivity().runOnUiThread {
                updateFromStreamPoint(lat, lon, tsMs, propsJson)
            }
        }
    }

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
        paramsLoadingOverlay = view.findViewById(R.id.paramsLoadingOverlay)
        paramsLoadingSpinner = view.findViewById(R.id.paramsLoadingSpinner)
        closeButton = view.findViewById(R.id.paramsCloseButton)

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
            }
        }
        // args is non-null here (we would have returned above if trackerId was null)
        val lastUpdateMs = args.getLong(ARG_LAST_UPDATE_MS, -1L)
        paramsLastUpdate.text = if (lastUpdateMs >= 0) formatTimeLocal(lastUpdateMs) else getString(R.string.no_points_yet)
        val lat = args.getDouble(ARG_POSITION_LAT, Double.NaN)
        val lon = args.getDouble(ARG_POSITION_LON, Double.NaN)
        paramsPosition.text = if (!lat.isNaN() && !lon.isNaN()) formatLatLon(lat, lon) else "-"

        loadTrackerData()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(LiveTrackStreamingService.BROADCAST_TRACK_POINT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(streamPointReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireContext().registerReceiver(streamPointReceiver, filter)
        }
        // Start streaming for this tracker when it's not the default (so we receive live updates like the website).
        val id = trackerId ?: return
        val defaultId = requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
            .getString("selected_tracker_id", "") ?: ""
        if (defaultId.isNotEmpty() && id != defaultId) {
            val name = arguments?.getString(ARG_TRACKER_NAME).orEmpty()
            val intent = Intent(requireContext(), LiveTrackStreamingService::class.java).apply {
                action = LiveTrackStreamingService.ACTION_START
                putExtra(LiveTrackStreamingService.EXTRA_TRACKER_ID, id)
                putExtra(LiveTrackStreamingService.EXTRA_TRACKER_NAME, name)
            }
            ContextCompat.startForegroundService(requireContext(), intent)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            requireContext().unregisterReceiver(streamPointReceiver)
        } catch (_: IllegalArgumentException) { /* already unregistered */ }
        // Stop streaming when params screen closes so we don't leave the service running.
        if (LiveTrackStreamingService.isRunning) {
            val intent = Intent(requireContext(), LiveTrackStreamingService::class.java).apply {
                action = LiveTrackStreamingService.ACTION_STOP
            }
            requireContext().startService(intent)
        }
    }

    private fun loadTrackerData() {
        val id = trackerId ?: return
        val defaultId = requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
            .getString("selected_tracker_id", "") ?: ""

        // Keep the existing name, last update, and position visible
        // Only hide the params grid and show loading spinner
        paramsGrid.visibility = View.GONE
        paramsWaitingCard.visibility = View.GONE
        paramsLoadingOverlay.visibility = View.VISIBLE
        paramsLoadingSpinner.start()

        // Default track: fill from local cache (geometryCache from map, etc.) when available.
        if (defaultId.isNotEmpty() && id == defaultId) {
            // Don't clear cache so getTrackerGeometry can return cached geometry/params if available.
        } else {
            TrackerRepository.clearCurrentTrackerCache()
        }

        // Important: only the single-tracker call drives the params UI. Use geometry endpoint for full track + params.
        TrackerRepository.getTrackerGeometry(requireContext(), id) { tracker ->
            if (isAdded) {
                requireActivity().runOnUiThread {
                    paramsLoadingSpinner.stop(hide = false)
                    paramsLoadingOverlay.visibility = View.GONE
                    if (tracker != null) bindTracker(tracker)
                }
            }
        }
        
        // Fire-and-forget: refresh trackers list in background so list is up to date when user goes back. Must not block.
        view?.post {
            TrackerRepository.getTrackers(requireContext(), forceRefresh = true) { _ ->
                if (isAdded) {
                    requireActivity().runOnUiThread {
                        requireActivity().supportFragmentManager.setFragmentResult(
                            TrackersFragment.REQUEST_UPDATE_LIST_FROM_CACHE,
                            Bundle()
                        )
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        paramsLoadingSpinner.stop()
    }

    /**
     * Update displayed params from a streamed point (same track as this fragment).
     * Called when we receive BROADCAST_TRACK_POINT for our trackerId so the params modal stays in sync.
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
            val entries = propsMap.entries.sortedBy { it.key }.map { ParamEntry(it.key, it.value) }
            paramsGrid.layoutManager = GridLayoutManager(requireContext(), 2)
            paramsGrid.adapter = ParamAdapter(requireContext(), entries)
        } else {
            paramsGrid.visibility = View.GONE
            paramsWaitingCard.visibility = View.VISIBLE
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
        val coords = tracker.geometry?.coordinates.orEmpty()
        val lastCoord = coords.lastOrNull()
        val lastTimestampMs = when {
            lastCoord != null && lastCoord.size >= 3 -> {
                (lastCoord[2] as? Number)?.toLong()?.let { n ->
                    if (n < 1e12) n * 1000 else n
                }
            }
            else -> null
        }
        val lastPosition = if (lastCoord != null && lastCoord.size >= 2) {
            val lon = (lastCoord[0] as? Number)?.toDouble()
            val lat = (lastCoord[1] as? Number)?.toDouble()
            if (lat != null && lon != null) Pair(lat, lon) else null
        } else null
        val pointParams = tracker.point_params.orEmpty()
        val latestPointParams = pointParams.lastOrNull() ?: emptyMap<String, Any?>()

        if (!tracker.name.isNullOrBlank()) {
            paramsName.visibility = View.VISIBLE
            paramsName.text = tracker.name.uppercase(Locale.getDefault())
        } else {
            paramsName.visibility = View.GONE
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
            val entries = latestPointParams.entries.sortedBy { it.key }.map { ParamEntry(it.key, it.value) }
            paramsGrid.layoutManager = GridLayoutManager(requireContext(), 2)
            paramsGrid.adapter = ParamAdapter(requireContext(), entries)
        } else if (lastTimestampMs != null || lastPosition != null) {
            paramsGrid.visibility = View.GONE
            paramsWaitingCard.visibility = View.VISIBLE
        } else {
            paramsGrid.visibility = View.GONE
            paramsWaitingCard.visibility = View.GONE
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

private fun usesImperialUnits(context: Context): Boolean {
    val locales = context.resources.configuration.locales
    val country = if (locales.size() > 0) locales.get(0).country else null
    return country in setOf("US", "LR", "MM")
}

private data class ParamEntry(val key: String, val value: Any?)

private class ParamAdapter(
    private val context: Context,
    private val entries: List<ParamEntry>
) : RecyclerView.Adapter<ParamAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_param_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (key, value) = entries[position]
        holder.label.text = PARAM_LABELS[key] ?: key
        holder.value.text = formatParamDisplay(context, key, value)
    }

    override fun getItemCount(): Int = entries.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val label: TextView = itemView.findViewById(R.id.paramLabel)
        val value: TextView = itemView.findViewById(R.id.paramValue)
    }
}

private val PARAM_LABELS = mapOf(
    "lat" to "Latitude",
    "lon" to "Longitude",
    "timestamp" to "Timestamp",
    "sat" to "Satellites",
    "desc" to "Description",
    "alt" to "Altitude",
    "acc" to "Accuracy",
    "bearing" to "Bearing",
    "prov" to "Provider",
    "spd_kph" to "Speed",
    "starttimestamp" to "Start Timestamp",
    "batt" to "Battery",
    "ischarging" to "Charging",
    "ser" to "Serial",
    "dist" to "Distance",
)

private const val METERS_TO_FEET = 3.28084
private const val METERS_TO_MILES = 0.000621371
private const val KMH_TO_MPH = 0.621371

private fun formatParamDisplay(context: Context, key: String, value: Any?): String {
    if (value == null) return ""
    val imperial = usesImperialUnits(context)
    when (key) {
        "alt", "acc" -> {
            val m = (value as? Number)?.toDouble() ?: return value.toString()
            return if (imperial) {
                val ft = (m * METERS_TO_FEET).toInt()
                "${ft} ${context.getString(R.string.unit_ft)}"
            } else {
                "${m.toInt()} ${context.getString(R.string.unit_m)}"
            }
        }
        "bearing" -> {
            val n = (value as? Number)?.toDouble() ?: return value.toString()
            return "%.0f°".format(Locale.US, n)
        }
        "prov" -> return value.toString().uppercase(Locale.getDefault())
        "spd_kph" -> {
            val kph = (value as? Number)?.toDouble() ?: return value.toString()
            return if (imperial) {
                val mph = (kph * KMH_TO_MPH).toInt()
                "$mph ${context.getString(R.string.unit_mph)}"
            } else {
                "${kph.toInt()} ${context.getString(R.string.unit_kmh)}"
            }
        }
        "starttimestamp" -> {
            val ms = when (value) {
                is Number -> if (value.toLong() < 1e12) value.toLong() * 1000 else value.toLong()
                else -> null
            }
            if (ms != null) {
                val sdf = SimpleDateFormat("MMM d, yyyy, h:mm:ss a", Locale.getDefault())
                return sdf.format(Date(ms))
            }
            return value.toString()
        }
        "batt" -> {
            val n = (value as? Number)?.toDouble() ?: return value.toString()
            return "%.0f%%".format(Locale.US, n)
        }
        "ischarging" -> return when (value) {
            true, "true", "1" -> "Yes"
            else -> "No"
        }
        "dist" -> {
            val m = (value as? Number)?.toDouble() ?: return value.toString()
            return if (imperial) {
                val ft = m * METERS_TO_FEET
                if (ft >= 5280) {
                    "%.1f ${context.getString(R.string.unit_mi)}".format(Locale.US, ft / 5280)
                } else {
                    "${ft.toInt()} ${context.getString(R.string.unit_ft)}"
                }
            } else {
                when {
                    m > 1000 -> "%.0f ${context.getString(R.string.unit_km)}".format(Locale.US, m / 1000)
                    else -> "${m.toInt()} ${context.getString(R.string.unit_m)}"
                }
            }
        }
        else -> return value.toString()
    }
}
