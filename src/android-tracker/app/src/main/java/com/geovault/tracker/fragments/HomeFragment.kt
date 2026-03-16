package com.geovault.tracker.fragments

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.preference.PreferenceManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.fragment.app.Fragment
import com.geovault.tracker.BuildConfig
import com.geovault.tracker.MainActivity
import com.geovault.tracker.R
import com.geovault.tracker.LiveTrackStreamingService
import com.geovault.tracker.TrackingService
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class HomeFragment : Fragment() {

    private lateinit var trackingStatusText: TextView
    private lateinit var trackingTrackNameText: TextView
    private lateinit var queueCountText: TextView
    private lateinit var sessionStatsContainer: View
    private lateinit var trackingDurationText: TextView
    private lateinit var lastPointSentText: TextView
    private lateinit var pointsSentSessionText: TextView
    private lateinit var startStopButton: MaterialButton
    private lateinit var currentLocationText: TextView
    private lateinit var distanceText: TextView
    private lateinit var accuracyText: TextView

    private lateinit var debugTrackModeText: TextView
    private lateinit var debugSimulateStreamButton: MaterialButton
    private lateinit var trackingContentContainer: View
    private lateinit var permissionsContainer: View
    private lateinit var radarDishIcon: android.widget.ImageView
    private lateinit var serverFailureOverlay: View
    private var isAccuracyRed = false

    private val homeScope = CoroutineScope(Dispatchers.Main + Job())
    private val sessionStatsHandler = Handler(Looper.getMainLooper())
    private var debugSimulationJob: Job? = null
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
            val location = IntentCompat.getParcelableExtra(intent, "location", android.location.Location::class.java)
            if (location != null) {
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
            updateQueueCount()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        trackingContentContainer = view.findViewById(R.id.trackingContentContainer)
        permissionsContainer = view.findViewById(R.id.permissionsContainer)
        radarDishIcon = view.findViewById(R.id.radarDishIcon)
        serverFailureOverlay = view.findViewById(R.id.serverFailureOverlay)
        
        trackingStatusText = view.findViewById(R.id.trackingStatusText)
        trackingTrackNameText = view.findViewById(R.id.trackingTrackNameText)
        debugTrackModeText = view.findViewById(R.id.debugTrackModeText)
        debugSimulateStreamButton = view.findViewById(R.id.debugSimulateStreamButton)
        queueCountText = view.findViewById(R.id.queueCountText)
        sessionStatsContainer = view.findViewById(R.id.sessionStatsContainer)
        trackingDurationText = view.findViewById(R.id.trackingDurationText)
        lastPointSentText = view.findViewById(R.id.lastPointSentText)
        pointsSentSessionText = view.findViewById(R.id.pointsSentSessionText)
        startStopButton = view.findViewById(R.id.startStopButton)
        currentLocationText = view.findViewById(R.id.currentLocationText)
        distanceText = view.findViewById(R.id.distanceText)
        accuracyText = view.findViewById(R.id.accuracyText)

        startStopButton.setOnClickListener {
            (requireActivity() as MainActivity).toggleTracking()
        }

        if (BuildConfig.DEBUG) {
            debugSimulateStreamButton.visibility = View.VISIBLE
            debugSimulateStreamButton.setOnClickListener { toggleDebugSimulation() }
        } else {
            debugSimulateStreamButton.visibility = View.GONE
        }

        setupPermissionButtons(view)
        updatePermissionsUi()
        updateTrackingUi()
        updateServerAccessibilityUi((requireActivity() as MainActivity).isServerAccessible)
        updateDebugTrackMode()
        updateQueueCount()
    }
    
    private fun setupPermissionButtons(view: View) {
        val mainActivity = requireActivity() as MainActivity
        
        view.findViewById<MaterialButton>(R.id.grantLocationButton).setOnClickListener {
            mainActivity.requestLocationPermission()
        }
        
        view.findViewById<MaterialButton>(R.id.grantBackgroundLocationButton).setOnClickListener {
            mainActivity.requestBackgroundLocationPermission()
        }
        
        view.findViewById<MaterialButton>(R.id.grantNotificationButton).setOnClickListener {
            mainActivity.requestNotificationPermission()
        }
        
        view.findViewById<MaterialButton>(R.id.grantBatteryButton).setOnClickListener {
            mainActivity.requestBatteryOptimizationExemption()
        }
    }
    
    fun updatePermissionsUi() {
        if (!::trackingContentContainer.isInitialized) return
        val mainActivity = requireActivity() as MainActivity
        
        if (mainActivity.hasAllRequiredPermissions()) {
            trackingContentContainer.visibility = View.VISIBLE
            permissionsContainer.visibility = View.GONE
        } else {
            trackingContentContainer.visibility = View.GONE
            permissionsContainer.visibility = View.VISIBLE
            
            view?.findViewById<MaterialButton>(R.id.grantLocationButton)?.apply {
                if (mainActivity.hasLocationPermission()) {
                    visibility = View.GONE
                } else {
                    visibility = View.VISIBLE
                }
            }
            
            view?.findViewById<MaterialButton>(R.id.grantBackgroundLocationButton)?.apply {
                if (mainActivity.hasBackgroundLocationPermission()) {
                    visibility = View.GONE
                } else {
                    visibility = View.VISIBLE
                }
            }
            
            view?.findViewById<MaterialButton>(R.id.grantNotificationButton)?.apply {
                if (mainActivity.hasNotificationPermission()) {
                    visibility = View.GONE
                } else {
                    visibility = View.VISIBLE
                }
            }
            
            view?.findViewById<MaterialButton>(R.id.grantBatteryButton)?.apply {
                if (mainActivity.hasBatteryOptimizationExemption()) {
                    visibility = View.GONE
                } else {
                    visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        
        val context = requireContext()
        ContextCompat.registerReceiver(
            context,
            locationReceiver,
            IntentFilter("com.geovault.tracker.LOCATION_UPDATE"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            context,
            sessionStatsReceiver,
            IntentFilter(TrackingService.SESSION_STATS_UPDATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        
        updatePermissionsUi()
        updateTrackingUi()
        updateDebugTrackMode()
        updateQueueCount()
        
        if (TrackingService.isRunning) {
            sessionStatsHandler.removeCallbacks(sessionStatsTicker)
            sessionStatsHandler.post(sessionStatsTicker)
        }
    }

    override fun onPause() {
        super.onPause()

        sessionStatsHandler.removeCallbacks(sessionStatsTicker)

        try {
            requireContext().unregisterReceiver(locationReceiver)
            requireContext().unregisterReceiver(sessionStatsReceiver)
        } catch (e: IllegalArgumentException) {
            // Already unregistered
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopDebugSimulation()
        homeScope.cancel()
    }

    /**
     * Debug-only: simulate live stream by broadcasting mock GPS points so the map
     * receives them via the same [LiveTrackStreamingService.BROADCAST_TRACK_POINT] path.
     */
    private fun toggleDebugSimulation() {
        if (debugSimulationJob?.isActive == true) {
            stopDebugSimulation()
            debugSimulateStreamButton.text = getString(R.string.debug_simulate_stream)
            return
        }
        val prefs = requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
        val trackId = prefs.getString("selected_tracker_id", "")?.trim().orEmpty()
        if (trackId.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.debug_select_tracker_first), Toast.LENGTH_LONG).show()
            return
        }
        debugSimulateStreamButton.text = getString(R.string.debug_stop_simulation)
        val intervalMs = 2000L
        val baseLat = 37.7749
        val baseLon = -122.4194
        val radiusDeg = 0.0003
        var pointIndex = 0
        debugSimulationJob = homeScope.launch(Dispatchers.Default) {
            while (isActive) {
                val angle = 2.0 * PI * pointIndex / 24.0
                val lat = baseLat + radiusDeg * sin(angle)
                val lon = baseLon + radiusDeg * cos(angle)
                pointIndex++
                val intent = Intent(LiveTrackStreamingService.BROADCAST_TRACK_POINT).apply {
                    setPackage(requireContext().packageName)
                    putExtra(LiveTrackStreamingService.EXTRA_TRACK_ID, trackId)
                    putExtra(LiveTrackStreamingService.EXTRA_POINT_LAT, lat)
                    putExtra(LiveTrackStreamingService.EXTRA_POINT_LON, lon)
                    putExtra(LiveTrackStreamingService.EXTRA_POINT_TS_MS, System.currentTimeMillis())
                    putExtra(LiveTrackStreamingService.EXTRA_ACCURACY_METERS, 10f)
                }
                withContext(Dispatchers.Main) {
                    if (isAdded) requireContext().sendBroadcast(intent)
                }
                delay(intervalMs)
            }
        }
    }

    private fun stopDebugSimulation() {
        debugSimulationJob?.cancel()
        debugSimulationJob = null
    }

    /** Show "Preparing" and "Stop Tracking" button while pre-tracking validation/setup is in progress. */
    fun showPreparingState() {
        trackingStatusText.text = getString(R.string.preparing)
        trackingStatusText.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.primary_blue)
        )
        startStopButton.text = getString(R.string.stop_tracking)
        updateTrackingTrackName()
    }

    fun updateServerAccessibilityUi(accessible: Boolean) {
        if (!::serverFailureOverlay.isInitialized) return
        serverFailureOverlay.visibility = if (accessible) View.GONE else View.VISIBLE
    }

    private fun updateDebugTrackMode() {
        if (!::debugTrackModeText.isInitialized) return
        val prefs = requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
        val autoEnabled = prefs.getBoolean(TrackingService.PREF_AUTO_TRACKING, false)
        if (!autoEnabled) {
            debugTrackModeText.visibility = View.GONE
            return
        }
        val profileIndex = prefs.getString(TrackingService.PREF_TRACKING_PROFILE, "1")?.toIntOrNull() ?: 1
        val modeResId = when (profileIndex) {
            0 -> R.string.profile_walking
            1 -> R.string.profile_biking
            2 -> R.string.profile_driving
            else -> R.string.unknown
        }
        val modeName = getString(modeResId)
        debugTrackModeText.text = getString(R.string.track_mode_label, modeName)
        debugTrackModeText.visibility = View.VISIBLE
    }

    private fun updateTrackingTrackName() {
        if (!::trackingTrackNameText.isInitialized) return
        val name = requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
            .getString("selected_tracker_name", null)?.takeIf { it.isNotBlank() }
        if (name != null) {
            trackingTrackNameText.text = name
            trackingTrackNameText.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
        } else {
            trackingTrackNameText.text = getString(R.string.no_tracker_selected).uppercase()
            trackingTrackNameText.setTextColor(ContextCompat.getColor(requireContext(), R.color.error_red))
        }
    }

    fun updateTrackingUi() {
        if (!::trackingStatusText.isInitialized) return
        val running = TrackingService.isRunning
        val acc = TrackingService.lastAccuracyMeters
        val noGoodFix = acc == null || acc > 152.4f
        val isLocking = running && noGoodFix

        trackingStatusText.text = getString(when {
            isLocking -> R.string.waiting_for_gps_lock
            running -> R.string.tracking_active
            else -> R.string.not_tracking
        })
        trackingStatusText.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                when {
                    isLocking -> R.color.warning_yellow
                    running -> R.color.warning_yellow
                    else -> R.color.primary_blue
                }
            )
        )
        startStopButton.text = getString(if (running) R.string.stop_tracking else R.string.start_tracking)
        updateTrackingTrackName()
        updateDebugTrackMode()

        // Change radar dish color based on tracking state
        if (running) {
            radarDishIcon.setColorFilter(
                ContextCompat.getColor(requireContext(), R.color.warning_yellow),
                android.graphics.PorterDuff.Mode.SRC_IN
            )
            sessionStatsHandler.removeCallbacks(sessionStatsTicker)
            sessionStatsHandler.post(sessionStatsTicker)
        } else {
            radarDishIcon.setColorFilter(
                ContextCompat.getColor(requireContext(), R.color.primary_blue),
                android.graphics.PorterDuff.Mode.SRC_IN
            )
            sessionStatsHandler.removeCallbacks(sessionStatsTicker)
        }
        
        updateSessionStats()
    }

    private fun updateSessionStats() {
        sessionStatsContainer.visibility = View.VISIBLE
        val running = TrackingService.isRunning
        if (!running) {
            trackingDurationText.text = "—"
            lastPointSentText.text = "—"
            pointsSentSessionText.text = "—"
            queueCountText.text = "—"
            distanceText.text = "—"
            accuracyText.text = "—"
            accuracyText.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            return
        }
        val startMs = TrackingService.sessionStartTimeMs
        val durationStr = if (startMs > 0) formatDurationMs(System.currentTimeMillis() - startMs) else "00:00:00"
        trackingDurationText.text = durationStr
        val lastAgo = formatTimeAgo(TrackingService.lastPointSentAtMs)
        lastPointSentText.text = if (lastAgo == "now") lastAgo else "-$lastAgo"
        pointsSentSessionText.text = TrackingService.pointsSentThisSession.toString()
        val useImperial = usesImperialUnits(requireContext())
        distanceText.text = formatDistance(TrackingService.sessionTotalDistanceMeters, useImperial)
        val acc = TrackingService.lastAccuracyMeters
        val isNoLock = acc != null && acc > 152.4f
        
        if (isNoLock) {
            accuracyText.text = "—"
            accuracyText.setTextColor(ContextCompat.getColor(requireContext(), R.color.error_red))
            isAccuracyRed = true
        } else {
            accuracyText.text = if (acc != null) formatAccuracy(acc, useImperial) else "—"
            val accuracyFilter = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getString(TrackingService.PREF_ACCURACY, "50")?.toFloatOrNull() ?: 50f
            
            // Hysteresis: turn red if > filter, stay red until < filter * 0.85
            if (acc != null) {
                if (acc > accuracyFilter) {
                    isAccuracyRed = true
                } else if (acc < accuracyFilter * 0.85f) {
                    isAccuracyRed = false
                }
            } else {
                isAccuracyRed = false
            }

            accuracyText.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (isAccuracyRed) R.color.error_red else R.color.text_primary
                )
            )
        }
    }

    private fun usesImperialUnits(context: Context): Boolean {
        return com.geovault.common.UnitUtils.usesImperialUnitsDefault(context)
    }

    private fun formatDistance(meters: Float, imperial: Boolean): String {
        if (imperial) {
            val feet = meters * 3.28084f
            return if (feet < 5280f) {
                getString(R.string.stat_distance_feet, feet.toInt())
            } else {
                getString(R.string.stat_distance_miles, feet / 5280f)
            }
        }
        return when {
            meters < 1000f -> getString(R.string.stat_distance_meters, meters.toInt())
            else -> getString(R.string.stat_distance_km, meters / 1000f)
        }
    }

    private fun formatAccuracy(meters: Float, imperial: Boolean): String {
        val value = if (imperial) (meters * 3.28084f).toInt() else meters.toInt()
        val resId = if (imperial) R.string.stat_accuracy_feet else R.string.stat_accuracy_meters
        return getString(resId, value)
    }

    private fun updateQueueCount() {
        if (!TrackingService.isRunning) {
            queueCountText.text = "—"
            return
        }
        val mainActivity = activity as? MainActivity ?: return
        mainActivity.updateQueueCountFromFragment(queueCountText)
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
}
