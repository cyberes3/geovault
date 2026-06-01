package com.geovault.tracker.positioning
import com.geovault.tracker.positioning.PositioningRuntime
import android.location.Location
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.R
import com.geovault.tracker.tracking.TrackingServiceConstants
import com.geovault.tracker.tracking.TrackingServiceIntents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ManualFixSubsystem(private val rt: PositioningRuntime) {
    fun handleManualSendPointCommand(): Boolean {
        if (!rt.state.isTracking) {
            rt.serviceScope.launch(Dispatchers.Main) {
                Toast.makeText(
                    rt.ports.service,
                    rt.ports.service.getString(R.string.manual_send_point_requires_active_tracking),
                    Toast.LENGTH_SHORT
                ).show()
            }
            return false
        }
        val selectedTrackerId = rt.ports.selectedTrackerId()
        if (!TrackingServiceIntents.hasValidSelectedTrackerId(selectedTrackerId)) {
            GeoVaultCaptureLog.w(TrackingServiceConstants.TAG, "Manual send ignored: invalid selected tracker id")
            return false
        }
        val candidate = rt.manualFix.getManualSendCandidateLocation() ?: run {
            GeoVaultCaptureLog.w(TrackingServiceConstants.TAG, "Manual send ignored: no candidate location available")
            return false
        }
        val manualLocation = rt.manualFix.buildManualSendLocation(candidate)
        rt.serviceScope.launch(Dispatchers.IO) {
            rt.fixIngest.processLocationUpdateSerialized(
                location = manualLocation,
                bypassFilters = true,
                allowWhenGpsPaused = true,
                skipAdaptiveTrackingEffects = true
            )
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    rt.ports.service,
                    rt.ports.service.getString(R.string.manual_send_point_sent),
                    Toast.LENGTH_SHORT
                ).show()
                rt.utilities.triggerLightHaptic()
            }
            rt.projection.updateNotificationFromDb(broadcastStats = true)
        }
        return true
    }

    fun getManualSendCandidateLocation(): Location? {
        rt.state.latestObservedRawLocation?.let { return Location(it) }
        rt.state.lowAccuracyFallbackCandidate?.let { return Location(it) }
        rt.state.lastFilteredLocation?.let { return Location(it) }
        return null
    }

    fun buildManualSendLocation(source: Location): Location {
        return Location(source).apply {
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            val sourceProvider = source.provider?.takeIf { it.isNotBlank() } ?: "fused"
            provider = "manual_send:$sourceProvider"
            val mergedExtras = Bundle().apply {
                source.extras?.let { putAll(it) }
                putBoolean(TrackingServiceConstants.EXTRAS_KEY_MANUAL_SEND, true)
            }
            extras = mergedExtras
        }
    }

}
