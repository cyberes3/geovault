package com.geovault.tracker.fragments

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.fragment.app.Fragment
import com.geovault.tracker.MainActivity
import com.geovault.tracker.R
import com.geovault.tracker.TrackingService
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*

class HomeFragment : Fragment() {

    private lateinit var trackingStatusText: TextView
    private lateinit var queueCountText: TextView
    private lateinit var sessionStatsContainer: View
    private lateinit var trackingDurationText: TextView
    private lateinit var lastPointSentText: TextView
    private lateinit var pointsSentSessionText: TextView
    private lateinit var startStopButton: MaterialButton
    private lateinit var currentLocationText: TextView
    private lateinit var distanceText: TextView
    private lateinit var accuracyText: TextView

    private lateinit var trackingContentContainer: View
    private lateinit var permissionsContainer: View
    private lateinit var radarDishIcon: android.widget.ImageView

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
        
        trackingStatusText = view.findViewById(R.id.trackingStatusText)
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
        
        setupPermissionButtons(view)
        updatePermissionsUi()
        updateTrackingUi()
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

    /** Show "Preparing" and "Stop Tracking" button while pre-tracking validation/setup is in progress. */
    fun showPreparingState() {
        trackingStatusText.text = getString(R.string.preparing)
        trackingStatusText.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.primary_blue)
        )
        startStopButton.text = getString(R.string.stop_tracking)
    }

    fun updateTrackingUi() {
        if (!::trackingStatusText.isInitialized) return
        val running = TrackingService.isRunning
        trackingStatusText.text = getString(if (running) R.string.tracking_active else R.string.not_tracking)
        trackingStatusText.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (running) R.color.warning_yellow else R.color.primary_blue
            )
        )
        startStopButton.text = getString(if (running) R.string.stop_tracking else R.string.start_tracking)
        
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
        accuracyText.text = if (acc != null) formatAccuracy(acc, useImperial) else "—"
    }

    private fun usesImperialUnits(context: Context): Boolean {
        val country = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val locales = context.resources.configuration.locales
            if (locales.size() > 0) locales.get(0).country else null
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale.country
        }
        return country in setOf("US", "LR", "MM")
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
