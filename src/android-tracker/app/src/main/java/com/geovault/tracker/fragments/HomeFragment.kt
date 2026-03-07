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
import com.google.android.gms.location.LocationServices
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.fragment.app.Fragment
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.RetrofitClient
import com.geovault.tracker.BinaryPayloadBuilder
import com.geovault.tracker.LiveTrackStreamingService
import com.geovault.tracker.MainActivity
import com.geovault.tracker.R
import com.geovault.tracker.TrackingService
import com.geovault.tracker.db.QueuedLocation
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

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

    private lateinit var trackingContentContainer: View
    private lateinit var permissionsContainer: View
    private lateinit var radarDishIcon: android.widget.ImageView
    private lateinit var testAddPointButton: MaterialButton
    private var testAddPointJob: Job? = null

    private val homeScope = CoroutineScope(Dispatchers.Main + Job())
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
        trackingTrackNameText = view.findViewById(R.id.trackingTrackNameText)
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

        testAddPointButton = view.findViewById(R.id.testAddPointButton)
        testAddPointButton.setOnClickListener { addRandomTestPoint() }

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

    override fun onDestroyView() {
        super.onDestroyView()
        testAddPointJob?.cancel()
        testAddPointJob = null
        homeScope.cancel()
    }

    /**
     * Toggles the test point loop: when not running, gets base location and starts adding one point
     * per second; when running, stops the loop.
     */
    private fun addRandomTestPoint() {
        if (testAddPointJob?.isActive == true) {
            testAddPointJob?.cancel()
            testAddPointJob = null
            testAddPointButton.text = getString(R.string.test_add_point_button)
            return
        }
        val prefs = requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
        val trackerIdStr = prefs.getString("selected_tracker_id", "") ?: ""
        if (trackerIdStr.isBlank()) {
            Toast.makeText(requireContext(), getString(R.string.test_add_point_select_tracker), Toast.LENGTH_SHORT).show()
            return
        }
        val trackerId = try {
            UUID.fromString(trackerIdStr)
        } catch (e: IllegalArgumentException) {
            Toast.makeText(requireContext(), getString(R.string.test_add_point_select_tracker), Toast.LENGTH_SHORT).show()
            return
        }
        val fusedClient = LocationServices.getFusedLocationProviderClient(requireContext())
        fusedClient.lastLocation.addOnSuccessListener { location ->
            val (baseLat, baseLon) = if (location != null) {
                Pair(location.latitude, location.longitude)
            } else {
                Pair(50.0, 8.0)
            }
            testAddPointButton.text = getString(R.string.test_stop_loop_button)
            testAddPointJob = homeScope.launch {
                while (isActive) {
                    val lat = baseLat + Random.nextDouble(-0.001, 0.001)
                    val lon = baseLon + Random.nextDouble(-0.001, 0.001)
                    sendTestPointToServer(trackerIdStr, trackerId, lat, lon, silent = true)
                    delay(1000L)
                }
            }
        }.addOnFailureListener {
            Toast.makeText(requireContext(), getString(R.string.test_add_point_error, it.message ?: "No location"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendTestPointToServer(trackerIdStr: String, trackerId: UUID, lat: Double, lon: Double, silent: Boolean = false) {
        val time = System.currentTimeMillis()
        val point = QueuedLocation(
            time = time,
            latitude = lat,
            longitude = lon,
            altitude = null,
            speed = null,
            bearing = null,
            accuracy = null,
            sat = null,
            prov = "test",
            dist = null
        )
        homeScope.launch {
            try {
                val serverUrl = withContext(Dispatchers.IO) {
                    GeovaultAuthManager.getServerUrl(requireContext()).trimEnd('/')
                }
                if (serverUrl.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        if (!silent) testAddPointButton.isEnabled = true
                        Toast.makeText(requireContext(), getString(R.string.test_add_point_error, "No server URL"), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
                val ingressUrl = "${baseUrl}api/extensions/live-track/app-ingress/"
                val payload = BinaryPayloadBuilder.buildPayloadMinimal(listOf(point), trackerId)
                val compressed = ByteArrayOutputStream().use { baos ->
                    GZIPOutputStream(baos).use { it.write(payload) }
                    baos.toByteArray()
                }
                val requestBody = compressed.toRequestBody("application/octet-stream".toMediaTypeOrNull())
                val request = Request.Builder()
                    .url(ingressUrl)
                    .addHeader("Content-Encoding", "gzip")
                    .post(requestBody)
                    .build()
                val client = RetrofitClient.getAuthenticatedOkHttpClient(requireContext())
                val response = withContext(Dispatchers.IO) {
                    client.newCall(request).execute()
                }
                withContext(Dispatchers.Main) {
                    if (!silent) testAddPointButton.isEnabled = true
                    if (!isAdded) return@withContext
                    if (response.isSuccessful) {
                        val intent = Intent(LiveTrackStreamingService.BROADCAST_TRACK_POINT).apply {
                            setPackage(requireContext().packageName)
                            putExtra(LiveTrackStreamingService.EXTRA_TRACK_ID, trackerIdStr)
                            putExtra(LiveTrackStreamingService.EXTRA_POINT_LAT, lat)
                            putExtra(LiveTrackStreamingService.EXTRA_POINT_LON, lon)
                            putExtra(LiveTrackStreamingService.EXTRA_POINT_TS_MS, time)
                        }
                        requireContext().sendBroadcast(intent)
                        if (!silent) {
                            Toast.makeText(requireContext(), getString(R.string.test_add_point_success), Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.test_add_point_error, "${response.code} ${response.message}"), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!silent) testAddPointButton.isEnabled = true
                    if (isAdded) {
                        Toast.makeText(requireContext(), getString(R.string.test_add_point_error, e.message ?: "Unknown error"), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
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
        trackingStatusText.text = getString(if (running) R.string.tracking_active else R.string.not_tracking)
        trackingStatusText.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (running) R.color.warning_yellow else R.color.primary_blue
            )
        )
        startStopButton.text = getString(if (running) R.string.stop_tracking else R.string.start_tracking)
        updateTrackingTrackName()

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
