package com.geovault.tracker.fragments

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.geovault.tracker.navigation.navHost
import com.geovault.tracker.R
import com.geovault.tracker.TrackingService
import com.geovault.tracker.services.TrackingRuntimeStateStore
import com.geovault.tracker.status.TrackingStatusPresentation
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint
import kotlin.math.roundToInt

@AndroidEntryPoint
class HomeFragment : Fragment() {
    companion object {
        private const val FEET_PER_METER = 3.28084f
        private const val MAX_DISPLAY_ACCURACY_FEET = 1500f
        private const val TOO_LOW_ACCURACY_DISPLAY = "-"
        private val MAX_DISPLAY_ACCURACY_METERS = MAX_DISPLAY_ACCURACY_FEET / FEET_PER_METER
    }

    private lateinit var homeContentRoot: LinearLayout
    private lateinit var trackingStatusText: TextView
    private lateinit var trackingTrackNameText: TextView
    private lateinit var queueCountText: TextView
    private lateinit var sessionStatsContainer: View
    private lateinit var trackingDurationText: TextView
    private lateinit var lastPointSentText: TextView
    private lateinit var pointsSentSessionText: TextView
    private lateinit var startStopButton: MaterialButton
    private lateinit var distanceText: TextView
    private lateinit var accuracyText: TextView

    private lateinit var trackingParamsButton: MaterialButton
    private lateinit var trackingContentContainer: LinearLayout
    private lateinit var permissionsContainer: View
    private lateinit var radarDishContainer: FrameLayout
    private lateinit var radarDishIcon: android.widget.ImageView
    private lateinit var serverFailureOverlay: View

    private var defaultRadarContainerSizePx = 0
    private var defaultRadarIconSizePx = 0
    private var defaultRadarContainerBottomMarginPx = 0
    private var defaultTrackingPaddingTopPx = 0
    private var defaultTrackingPaddingBottomPx = 0
    private var minRadarContainerSizePx = 0
    private var minRadarIconSizePx = 0
    private var minRadarContainerBottomMarginPx = 0
    private var minTrackingPaddingTopPx = 0
    private var minTrackingPaddingBottomPx = 0
    private var homeLayoutChangeListener: View.OnLayoutChangeListener? = null

    private val sessionStatsHandler = Handler(Looper.getMainLooper())
    private val sessionStatsTickerIntervalMs = 1000L

    private val sessionStatsTicker = object : Runnable {
        override fun run() {
            if (!trackingSnapshot().isRunning) return
            updateTrackingStatusHeader()
            updateSessionStats()
            updateQueueCount()
            sessionStatsHandler.postDelayed(this, sessionStatsTickerIntervalMs)
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

        homeContentRoot = view.findViewById(R.id.homeContentRoot)
        trackingContentContainer = view.findViewById(R.id.trackingContentContainer)
        permissionsContainer = view.findViewById(R.id.permissionsContainer)
        radarDishContainer = view.findViewById(R.id.radarDishContainer)
        radarDishIcon = view.findViewById(R.id.radarDishIcon)
        serverFailureOverlay = view.findViewById(R.id.serverFailureOverlay)
        
        trackingStatusText = view.findViewById(R.id.trackingStatusText)
        trackingTrackNameText = view.findViewById(R.id.trackingTrackNameText)
        trackingParamsButton = view.findViewById(R.id.trackingParamsButton)
        queueCountText = view.findViewById(R.id.queueCountText)
        sessionStatsContainer = view.findViewById(R.id.sessionStatsContainer)
        trackingDurationText = view.findViewById(R.id.trackingDurationText)
        lastPointSentText = view.findViewById(R.id.lastPointSentText)
        pointsSentSessionText = view.findViewById(R.id.pointsSentSessionText)
        startStopButton = view.findViewById(R.id.startStopButton)
        distanceText = view.findViewById(R.id.distanceText)
        accuracyText = view.findViewById(R.id.accuracyText)

        defaultRadarContainerSizePx = radarDishContainer.layoutParams.height
        defaultRadarIconSizePx = radarDishIcon.layoutParams.height
        defaultRadarContainerBottomMarginPx =
            (radarDishContainer.layoutParams as? LinearLayout.LayoutParams)?.bottomMargin ?: 0
        defaultTrackingPaddingTopPx = trackingContentContainer.paddingTop
        defaultTrackingPaddingBottomPx = trackingContentContainer.paddingBottom
        minRadarContainerSizePx = dpToPx(72f)
        minRadarIconSizePx = dpToPx(52f)
        minRadarContainerBottomMarginPx = 0
        minTrackingPaddingTopPx = dpToPx(10f)
        minTrackingPaddingBottomPx = dpToPx(10f)
        homeLayoutChangeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyDynamicRadarSizing()
        }
        homeContentRoot.addOnLayoutChangeListener(homeLayoutChangeListener)
        homeContentRoot.post { applyDynamicRadarSizing() }

        startStopButton.setOnClickListener {
            navHost()?.toggleTracking()
        }

        trackingParamsButton.setOnClickListener {
            val host = navHost() ?: return@setOnClickListener
            val runtime = trackingSnapshot()
            val id = runtime.selectedTrackerId
            if (id.isBlank()) return@setOnClickListener
            val name = runtime.selectedTrackerName.takeIf { it.isNotBlank() }
            val lastMs = when {
                runtime.lastTrackedTimestampMs > 0L -> runtime.lastTrackedTimestampMs
                runtime.lastPointSentAtMs > 0L -> runtime.lastPointSentAtMs
                else -> null
            }
            val lat = runtime.lastTrackedLatitude
            val lon = runtime.lastTrackedLongitude
            host.showTrackerParamsFragment(
                id,
                name,
                lastUpdateMs = lastMs,
                positionLat = lat,
                positionLon = lon
            )
        }

        setupPermissionButtons(view)
        updatePermissionsUi()
        updateTrackingUi()
        updateServerAccessibilityUi(navHost()?.isServerAccessible ?: true)
        updateQueueCount()
    }
    
    private fun setupPermissionButtons(view: View) {
        val host = navHost() ?: return
        
        view.findViewById<MaterialButton>(R.id.grantLocationButton).setOnClickListener {
            host.requestLocationPermission()
        }
        
        view.findViewById<MaterialButton>(R.id.grantBackgroundLocationButton).setOnClickListener {
            host.requestBackgroundLocationPermission()
        }
        
        view.findViewById<MaterialButton>(R.id.grantNotificationButton).setOnClickListener {
            host.requestNotificationPermission()
        }
        
        view.findViewById<MaterialButton>(R.id.grantBatteryButton).setOnClickListener {
            host.requestBatteryOptimizationExemption()
        }

        view.findViewById<MaterialButton>(R.id.grantExactAlarmButton).setOnClickListener {
            host.requestExactAlarmPermission()
        }
    }
    
    fun updatePermissionsUi() {
        if (!::trackingContentContainer.isInitialized) return
        val host = navHost() ?: return
        
        if (host.hasAllRequiredPermissions()) {
            trackingContentContainer.visibility = View.VISIBLE
            permissionsContainer.visibility = View.GONE
        } else {
            trackingContentContainer.visibility = View.GONE
            permissionsContainer.visibility = View.VISIBLE
            
            view?.findViewById<MaterialButton>(R.id.grantLocationButton)?.apply {
                if (host.hasLocationPermission()) {
                    visibility = View.GONE
                } else {
                    visibility = View.VISIBLE
                }
            }
            
            view?.findViewById<MaterialButton>(R.id.grantBackgroundLocationButton)?.apply {
                if (host.hasBackgroundLocationPermission()) {
                    visibility = View.GONE
                } else {
                    visibility = View.VISIBLE
                }
            }
            
            view?.findViewById<MaterialButton>(R.id.grantNotificationButton)?.apply {
                if (host.hasNotificationPermission()) {
                    visibility = View.GONE
                } else {
                    visibility = View.VISIBLE
                }
            }
            
            view?.findViewById<MaterialButton>(R.id.grantBatteryButton)?.apply {
                if (host.hasBatteryOptimizationExemption()) {
                    visibility = View.GONE
                } else {
                    visibility = View.VISIBLE
                }
            }

            view?.findViewById<MaterialButton>(R.id.grantExactAlarmButton)?.apply {
                if (host.hasExactAlarmPermission()) {
                    visibility = View.GONE
                } else {
                    visibility = View.VISIBLE
                }
            }
        }

        if (::homeContentRoot.isInitialized) {
            homeContentRoot.post { applyDynamicRadarSizing() }
        }
    }

    override fun onResume() {
        super.onResume()
        
        val context = requireContext()
        ContextCompat.registerReceiver(
            context,
            sessionStatsReceiver,
            IntentFilter(TrackingService.SESSION_STATS_UPDATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        
        updatePermissionsUi()
        updateTrackingUi()
        updateQueueCount()
        homeContentRoot.post { applyDynamicRadarSizing() }
        
        if (trackingSnapshot().isRunning) {
            sessionStatsHandler.removeCallbacks(sessionStatsTicker)
            sessionStatsHandler.post(sessionStatsTicker)
        }
    }

    override fun onPause() {
        super.onPause()

        sessionStatsHandler.removeCallbacks(sessionStatsTicker)

        try {
            requireContext().unregisterReceiver(sessionStatsReceiver)
        } catch (e: IllegalArgumentException) {
            // Already unregistered
        }
    }

    override fun onDestroyView() {
        homeLayoutChangeListener?.let { listener ->
            if (::homeContentRoot.isInitialized) {
                homeContentRoot.removeOnLayoutChangeListener(listener)
            }
        }
        homeLayoutChangeListener = null
        super.onDestroyView()
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

    private fun updateTrackingTrackName() {
        if (!::trackingTrackNameText.isInitialized) return
        val runtime = trackingSnapshot()
        val selectedTrackerId = runtime.selectedTrackerId
        val name = runtime.selectedTrackerName.takeIf { it.isNotBlank() }
        if (name != null) {
            trackingTrackNameText.text = name
            trackingTrackNameText.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
        } else {
            trackingTrackNameText.text = getString(R.string.no_tracker_selected).uppercase()
            val colorRes = if (selectedTrackerId.isBlank()) R.color.error_red else R.color.text_secondary
            trackingTrackNameText.setTextColor(ContextCompat.getColor(requireContext(), colorRes))
        }
    }

    fun updateTrackingUi() {
        if (!::trackingStatusText.isInitialized) return
        updateTrackingStatusHeader()
        val runtime = trackingSnapshot()
        val running = runtime.isRunning
        startStopButton.text = getString(if (running) R.string.stop_tracking else R.string.start_tracking)
        updateTrackingTrackName()
        if (::trackingParamsButton.isInitialized) {
            trackingParamsButton.visibility =
                if (running && runtime.selectedTrackerId.isNotBlank()) View.VISIBLE else View.INVISIBLE
        }

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
        if (::homeContentRoot.isInitialized) {
            homeContentRoot.post { applyDynamicRadarSizing() }
        }
    }

    private fun updateTrackingStatusHeader() {
        if (!::trackingStatusText.isInitialized) return
        val runtime = trackingSnapshot()
        val status = runtime.uiStatus
        trackingStatusText.text = getString(TrackingStatusPresentation.statusTextRes(status))
        trackingStatusText.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                TrackingStatusPresentation.statusColorRes(status)
            )
        )
    }

    private fun updateSessionStats() {
        sessionStatsContainer.visibility = View.VISIBLE
        val runtime = trackingSnapshot()
        val running = runtime.isRunning
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
        val startMs = runtime.sessionStartTimeMs
        val durationStr = if (startMs > 0) formatDurationMs(System.currentTimeMillis() - startMs) else "00:00:00"
        trackingDurationText.text = durationStr
        val lastAgo = formatTimeAgo(runtime.lastPointSentAtMs)
        lastPointSentText.text = if (lastAgo == "now") lastAgo else "-$lastAgo"
        pointsSentSessionText.text = runtime.pointsSentThisSession.toString()
        val useImperial = usesImperialUnits(requireContext())
        distanceText.text = formatDistance(runtime.sessionTotalDistanceMeters, useImperial)
        val acc = runtime.lastAccuracyMeters
        if (acc == null) {
            accuracyText.text = TOO_LOW_ACCURACY_DISPLAY
            accuracyText.setTextColor(ContextCompat.getColor(requireContext(), R.color.error_red))
            return
        }
        if (acc > MAX_DISPLAY_ACCURACY_METERS) {
            accuracyText.text = TOO_LOW_ACCURACY_DISPLAY
            accuracyText.setTextColor(ContextCompat.getColor(requireContext(), R.color.error_red))
            return
        }

        accuracyText.text = formatAccuracy(acc, useImperial)
        val accuracyFilter = runtime.effectiveAccuracyThresholdMeters
        val accuracyColor = if (acc > accuracyFilter) R.color.error_red else R.color.text_primary
        accuracyText.setTextColor(ContextCompat.getColor(requireContext(), accuracyColor))
    }

    private fun usesImperialUnits(context: Context): Boolean {
        return com.geovault.common.UnitUtils.usesImperialUnitsDefault(context)
    }

    private fun formatDistance(meters: Float, imperial: Boolean): String {
        if (imperial) {
            val feet = meters * FEET_PER_METER
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
        val value = if (imperial) (meters * FEET_PER_METER).toInt() else meters.toInt()
        val resId = if (imperial) R.string.stat_accuracy_feet else R.string.stat_accuracy_meters
        return getString(resId, value)
    }

    private fun updateQueueCount() {
        if (!trackingSnapshot().isRunning) {
            queueCountText.text = "—"
            return
        }
        queueCountText.text = trackingSnapshot().queuedPointsVisible.toString()
    }

    private fun trackingSnapshot() = TrackingRuntimeStateStore.state.value

    private fun applyDynamicRadarSizing() {
        if (!::homeContentRoot.isInitialized || !::trackingContentContainer.isInitialized || !::radarDishContainer.isInitialized) {
            return
        }
        if (trackingContentContainer.visibility != View.VISIBLE) {
            applyRadarSizing(defaultRadarContainerSizePx, defaultRadarIconSizePx, defaultRadarContainerBottomMarginPx)
            applyTrackingContainerPadding(defaultTrackingPaddingTopPx, defaultTrackingPaddingBottomPx)
            return
        }

        var requiredHeight = 0
        for (index in 0 until trackingContentContainer.childCount) {
            val child = trackingContentContainer.getChildAt(index)
            if (child.visibility == View.GONE) continue

            val params = child.layoutParams as? LinearLayout.LayoutParams
            if ((params?.weight ?: 0f) > 0f) continue

            val childHeight = child.height.takeIf { it > 0 } ?: child.measuredHeight
            requiredHeight += childHeight + (params?.verticalMargins() ?: 0)
        }

        val availableHeight = trackingContentContainer.height
        if (availableHeight <= 0) return

        val overflow = requiredHeight + trackingContentContainer.paddingTop + trackingContentContainer.paddingBottom - availableHeight
        if (overflow <= 0) {
            applyRadarSizing(defaultRadarContainerSizePx, defaultRadarIconSizePx, defaultRadarContainerBottomMarginPx)
            applyTrackingContainerPadding(defaultTrackingPaddingTopPx, defaultTrackingPaddingBottomPx)
            return
        }

        var remainingOverflow = overflow
        val maxContainerReduction = defaultRadarContainerSizePx - minRadarContainerSizePx
        val containerReduction = remainingOverflow.coerceAtMost(maxContainerReduction)
        val containerSizePx = defaultRadarContainerSizePx - containerReduction
        remainingOverflow -= containerReduction

        val maxMarginReduction = defaultRadarContainerBottomMarginPx - minRadarContainerBottomMarginPx
        val marginReduction = remainingOverflow.coerceAtMost(maxMarginReduction)
        val containerBottomMarginPx = defaultRadarContainerBottomMarginPx - marginReduction
        remainingOverflow -= marginReduction

        val maxTopPaddingReduction = defaultTrackingPaddingTopPx - minTrackingPaddingTopPx
        val topPaddingReduction = remainingOverflow.coerceAtMost(maxTopPaddingReduction)
        val trackingTopPaddingPx = defaultTrackingPaddingTopPx - topPaddingReduction
        remainingOverflow -= topPaddingReduction

        val maxBottomPaddingReduction = defaultTrackingPaddingBottomPx - minTrackingPaddingBottomPx
        val bottomPaddingReduction = remainingOverflow.coerceAtMost(maxBottomPaddingReduction)
        val trackingBottomPaddingPx = defaultTrackingPaddingBottomPx - bottomPaddingReduction
        val scale = containerSizePx.toFloat() / defaultRadarContainerSizePx.toFloat()
        val iconSizePx = (defaultRadarIconSizePx * scale)
            .roundToInt()
            .coerceIn(minRadarIconSizePx, defaultRadarIconSizePx)
        applyRadarSizing(containerSizePx, iconSizePx, containerBottomMarginPx)
        applyTrackingContainerPadding(trackingTopPaddingPx, trackingBottomPaddingPx)
    }

    private fun applyRadarSizing(containerSizePx: Int, iconSizePx: Int, bottomMarginPx: Int) {
        updateSquareLayoutSize(radarDishContainer, containerSizePx)
        updateBottomMargin(radarDishContainer, bottomMarginPx)
        updateSquareLayoutSize(radarDishIcon, iconSizePx)
    }

    private fun applyTrackingContainerPadding(topPx: Int, bottomPx: Int) {
        if (trackingContentContainer.paddingTop == topPx && trackingContentContainer.paddingBottom == bottomPx) return
        trackingContentContainer.setPadding(
            trackingContentContainer.paddingLeft,
            topPx,
            trackingContentContainer.paddingRight,
            bottomPx
        )
    }

    private fun updateSquareLayoutSize(view: View, sizePx: Int) {
        val params = view.layoutParams ?: return
        if (params.width == sizePx && params.height == sizePx) return
        params.width = sizePx
        params.height = sizePx
        view.layoutParams = params
    }

    private fun updateBottomMargin(view: View, bottomMarginPx: Int) {
        val params = view.layoutParams as? LinearLayout.LayoutParams ?: return
        if (params.bottomMargin == bottomMarginPx) return
        params.bottomMargin = bottomMarginPx
        view.layoutParams = params
    }

    private fun LinearLayout.LayoutParams.verticalMargins(): Int = topMargin + bottomMargin

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        ).roundToInt()
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
