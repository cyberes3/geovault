package com.geovault.tracker.positioning
import com.geovault.tracker.positioning.PositioningRuntime
import android.content.Intent
import android.location.Location
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.positioning.RecordingPace
import com.geovault.tracker.tracking.TrackingServiceConstants
import com.geovault.tracker.tracking.TrackingServiceIntents

internal class CommandDiagnosticsSubsystem(private val rt: PositioningRuntime) {
    fun logBackgroundWakeupDiagnostics(
        path: TrackingServiceIntents.StartupCommandPath,
        foregroundStartRequired: Boolean,
        intent: Intent?,
    ) {
        if (
            path != TrackingServiceIntents.StartupCommandPath.LocationUpdate &&
            !foregroundStartRequired
        ) {
            return
        }
        val nowMs = System.currentTimeMillis()
        val incomingLocations = if (path == TrackingServiceIntents.StartupCommandPath.LocationUpdate) {
            TrackingServiceIntents.extractLocationUpdateIntentLocations(intent)
        } else {
            emptyList()
        }
        val diagnosticLocation = incomingLocations.lastOrNull()
            ?: rt.state.latestObservedRawLocation?.let { Location(it) }
        val locationAgeMs = diagnosticLocation
            ?.takeIf { it.time > 0L }
            ?.let { nowMs - it.time }
        val locationAccuracyMeters = diagnosticLocation
            ?.takeIf { it.hasAccuracy() }
            ?.accuracy
        val source = intent?.getStringExtra(TrackingServiceIntents.EXTRA_BACKGROUND_WAKEUP_SOURCE) ?: "direct"
        val details = "path=$path foregroundStartRequired=$foregroundStartRequired source=$source " +
            "isTracking=${rt.state.isTracking} startupInProgress=${rt.state.startupInProgress} gpsState=${rt.state.gpsRuntimeState} " +
            "collectionPace=${rt.state.collectionPace} " +
            "paused=${rt.state.collectionPace == RecordingPace.Stationary} " +
            "incomingCount=${incomingLocations.size} " +
            "lastRawAgeMs=${locationAgeMs ?: -1L} lastRawAccuracy=${locationAccuracyMeters ?: -1f} " +
            "provider=${diagnosticLocation?.provider ?: "none"}"
        GeoVaultCaptureLog.i(TrackingServiceConstants.TAG, "Background wakeup diagnostics $details")
        rt.deps.runtimeTelemetry.event("background_wakeup", details)
    }

    fun summarizeLocationForTelemetry(location: Location?): String {
        if (location == null) return "none"
        val ageMs = location.time.takeIf { it > 0L }?.let { System.currentTimeMillis() - it }
        val accuracy = if (location.hasAccuracy()) location.accuracy else null
        return "provider=${location.provider ?: "unknown"},ageMs=${ageMs ?: -1L},accuracy=${accuracy ?: -1f}"
    }

    fun handleLocationUpdateCommand(intent: Intent?): Boolean {
        val locations = TrackingServiceIntents.extractLocationUpdateIntentLocations(intent)
        if (!rt.state.isTracking) {
            rt.deps.runtimeTelemetry.event(
                "location_update_dropped",
                "reason=not_tracking count=${locations.size} gpsState=${rt.state.gpsRuntimeState}"
            )
            return false
        }
        rt.deps.runtimeTelemetry.event(
            "location_update_received",
            "count=${locations.size} gpsState=${rt.state.gpsRuntimeState} " +
                "foregroundStartRequired=${intent?.getBooleanExtra(TrackingServiceIntents.EXTRA_FOREGROUND_SERVICE_START_REQUIRED, false) == true}"
        )
        locations.forEach { location ->
            val snapshot = Location(location)
            rt.locationListener.onLocationChanged(snapshot)
        }
        return true
    }

}
