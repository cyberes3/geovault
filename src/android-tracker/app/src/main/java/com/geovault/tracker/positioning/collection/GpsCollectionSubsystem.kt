package com.geovault.tracker.positioning.collection
import com.geovault.tracker.positioning.PositioningRuntime
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.location.RecoveryAnchorState
import com.geovault.tracker.location.TrackingControlEvent
import com.geovault.tracker.policy.TrackPointPolicyEngine
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.positioning.config.GpsRuntimeEvent
import com.geovault.tracker.positioning.config.GpsRuntimeState
import com.geovault.tracker.positioning.config.GpsRuntimeStateMachine
import com.geovault.tracker.services.TrackingMotionMode
import com.geovault.tracker.tracking.TrackingServiceConstants
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class GpsCollectionSubsystem(private val rt: PositioningRuntime) {
    fun enterStationaryRegion(
        anchorLocation: Location,
        nowMs: Long,
        motionMode: TrackingMotionMode,
        radiusMeters: Float,
    ) {
        val trackerId = rt.ports.selectedTrackerId()
        if (trackerId.isBlank()) return
        val anchor = RecoveryAnchorState.fromLocation(
            trackerId = trackerId,
            sessionBoundaryId = rt.state.sessionVisibleBoundaryId,
            location = anchorLocation,
            radiusMeters = radiusMeters,
            source = "stationary_region",
            motionMode = motionMode,
        )
        rt.deps.stationaryFreshnessCoordinator.enterRegion(anchor = anchor, nowMs = nowMs)
        rt.state.recoveryAnchorState = anchor
        rt.deps.recoveryAnchorStore.save(anchor)
    }

    fun ensureGpsProviderReceiverRegistered() {
        if (rt.state.gpsProviderReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(
            rt.ports.service,
            rt.gpsProviderReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        rt.state.gpsProviderReceiverRegistered = true
    }

    fun unregisterGpsProviderReceiverIfNeeded() {
        if (!rt.state.gpsProviderReceiverRegistered) return
        runCatching { rt.ports.service.unregisterReceiver(rt.gpsProviderReceiver) }
        rt.state.gpsProviderReceiverRegistered = false
    }

    fun enterWaitingForGpsProvider(reason: String) {
        if (
            !rt.state.isTracking ||
            rt.state.gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER ||
            rt.state.gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
        ) {
            return
        }
        rt.collection.transitionGpsState(GpsRuntimeEvent.PROVIDER_DISABLED, reason)
        if (rt.state.gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED) {
            rt.deps.stationaryFreshnessCoordinator.onProviderPaused(reason = reason)
        } else {
            rt.deps.stationaryFreshnessCoordinator.onResumed(reason = "provider_disabled")
        }
        rt.motion.resetElasticDistanceOverride(reason = "gps_provider_disabled", reapplyRequest = false)
        rt.recovery.fastLock.stopFastGpsLockWindow(reason = "gps_provider_disabled")
        rt.recovery.fallback.cancelLowAccuracyFallbackTimer(clearCandidate = false)
        rt.recovery.pausedFreshness.clearPausedFreshnessProbe(reason = "gps_provider_disabled")
        rt.lifecycle.stopLocationUpdates()
        GeoVaultCaptureLog.w(TrackingServiceConstants.TAG, "GPS provider disabled while tracking reason=$reason")
        rt.deps.runtimeTelemetry.event("gps_provider_disabled", "reason=$reason")
        rt.projection.syncRuntimeStateStore()
        rt.projection.updateNotificationFromDb(broadcastStats = true)
    }

    fun resumeFromGpsProviderWait(reason: String) {
        if (
            !rt.state.isTracking ||
            (rt.state.gpsRuntimeState != GpsRuntimeState.WAITING_FOR_PROVIDER &&
                rt.state.gpsRuntimeState != GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED)
        ) {
            return
        }
        rt.collection.transitionGpsState(GpsRuntimeEvent.PROVIDER_ENABLED, reason)
        if (rt.state.gpsRuntimeState == GpsRuntimeState.PAUSED_FOR_MOTION) {
            rt.deps.stationaryFreshnessCoordinator.onProviderRestored(reason = reason)
            if (rt.state.gpsRuntimeState != GpsRuntimeState.PAUSED_FOR_MOTION) {
                return
            }
            GeoVaultCaptureLog.i(TrackingServiceConstants.TAG, "GPS provider re-enabled while paused reason=$reason")
            rt.projection.syncRuntimeStateStore()
            rt.projection.updateNotificationFromDb(broadcastStats = true)
            return
        }
        if (rt.utilities.isWaitingForProviderState()) {
            rt.projection.syncRuntimeStateStore()
            rt.projection.updateNotificationFromDb(broadcastStats = true)
            return
        }
        if (!rt.locationRequests.applyCurrentLocationRequest("gps_provider_reenabled_$reason")) {
            rt.foreground.failActiveTrackingAndStop(rt.locationRequests.resolveLocationRequestFailureMessage())
            return
        }
        GeoVaultCaptureLog.i(TrackingServiceConstants.TAG, "GPS provider re-enabled, resumed updates reason=$reason")
        rt.projection.syncRuntimeStateStore()
        rt.projection.updateNotificationFromDb(broadcastStats = true)
    }

    fun pauseGps() {
        rt.collection.pauseGpsInternal(force = false)
    }

    fun pauseGpsInternal(force: Boolean) {
        if (!rt.state.isTracking) return
        if (
            !force &&
            (rt.state.gpsRuntimeState == GpsRuntimeState.PAUSED_FOR_MOTION ||
                rt.state.gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED)
        ) {
            return
        }
        if (rt.deps.significantMotionBridge?.isAvailable() != true) {
            rt.deps.runtimeTelemetry.event("gps_pause_skipped", "reason=significant_motion_unavailable")
            return
        }
        rt.collection.transitionGpsState(GpsRuntimeEvent.PAUSE_FOR_MOTION, "pause_for_motion")
        rt.projection.transitionControlState(TrackingControlEvent.PauseRequested)
        rt.motion.resetElasticDistanceOverride(reason = "gps_paused", reapplyRequest = false)
        rt.recovery.fastLock.stopFastGpsLockWindow(reason = "gps_paused")
        rt.lifecycle.stopLocationUpdates()
        rt.deps.autoTrackingMotionEngine.onGpsPaused(rt.deps.clock.wallTimeMs())
        rt.deps.autoTrackingMotionCoordinator.clearEvidenceCandidate()
        rt.deps.significantMotionBridge?.request()
        rt.state.sigMotionSensorStartTime = rt.deps.clock.wallTimeMs()
        rt.collection.startSensorWatchdog()
        rt.deps.stationaryFreshnessCoordinator.schedulePausedPing(
            reason = "pause_for_motion",
            providerAvailable = rt.utilities.isGpsProviderEnabled()
        )
        rt.projection.syncRuntimeStateStore()
        rt.projection.updateNotificationFromDb(broadcastStats = true)
    }

    fun startSensorWatchdog() {
        rt.state.watchdogJob?.cancel()
        rt.state.watchdogJob = rt.serviceScope.launch {
            while (
                rt.state.gpsRuntimeState == GpsRuntimeState.PAUSED_FOR_MOTION ||
                rt.state.gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
            ) {
                delay(60_000L)
                rt.collection.requestStationaryFreshnessProbeIfDue(reason = "sensor_watchdog")
                val age = rt.deps.clock.wallTimeMs() - rt.state.sigMotionSensorStartTime
                if (age > 5 * 60_000L) {
                    rt.deps.significantMotionBridge?.cancel()
                    rt.deps.significantMotionBridge?.request()
                    rt.state.sigMotionSensorStartTime = rt.deps.clock.wallTimeMs()
                    rt.deps.runtimeTelemetry.event("sensor_watchdog_refresh", "ageMs=$age")
                }
            }
        }
    }

    fun requestStationaryFreshnessProbeIfDue(reason: String) {
        if (
            !rt.state.isTracking ||
            (rt.state.gpsRuntimeState != GpsRuntimeState.PAUSED_FOR_MOTION &&
                rt.state.gpsRuntimeState != GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED)
        ) {
            return
        }
        val dueAtMs = rt.deps.stationaryFreshnessCoordinator.nextFreshnessDueAtMs(
            intervalMs = rt.contextBuilder.currentPositioningRuntimeContext().stationaryProbeIntervalMs
        ) ?: return
        val nowMs = rt.deps.clock.wallTimeMs()
        if (nowMs < dueAtMs) return
        rt.deps.runtimeTelemetry.event(
            "stationary_ping_due_reconciled",
            "reason=$reason overdueMs=${nowMs - dueAtMs} state=${rt.state.gpsRuntimeState}"
        )
        rt.recovery.pausedFreshness.requestStationaryFreshnessProbe(reason = reason)
    }

    fun resumeGps(reason: String = "significant_motion_resume") {
        if (
            !rt.state.isTracking ||
            (rt.state.gpsRuntimeState != GpsRuntimeState.PAUSED_FOR_MOTION &&
                rt.state.gpsRuntimeState != GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED)
        ) {
            return
        }
        rt.collection.transitionGpsState(GpsRuntimeEvent.RESUME_FROM_MOTION, reason)
        rt.projection.transitionControlState(TrackingControlEvent.ResumeRequested)
        // Mark the next fix as a resume boundary. Real movement should be
        // accepted, but a false motion wakeup while stationary should still
        // be able to snap back to the pre-pause anchor.
        rt.ports.selectedTrackerId().takeIf { it.isNotBlank() }?.let { trackerId ->
            TrackPointPolicyEngine.notifyMotionChanged(
                source = TrackPointSource.LOCAL_GPS,
                trackId = trackerId,
            )
        }
        rt.motion.resetElasticDistanceOverride(reason = "gps_resumed", reapplyRequest = false)
        rt.recovery.fastLock.stopFastGpsLockWindow(reason = "gps_resumed")
        if (rt.deps.lowAccuracyFallbackCoordinator.hasPendingCandidate()) {
            rt.recovery.fallback.ensureLowAccuracyFallbackTimerRunning()
            rt.deps.runtimeTelemetry.event("fallback_preserved_on_resume", "reason=$reason")
        }
        rt.deps.stationaryFreshnessCoordinator.onResumed(reason = reason)
        rt.state.consecutiveStationaryPoints = 0
        rt.state.stationaryAnchorLocation = null
        rt.deps.stationaryFreshnessCoordinator.clearRegion()
        rt.deps.autoTrackingMotionEngine.onGpsResumed(rt.deps.clock.wallTimeMs())
        rt.state.watchdogJob?.cancel()
        rt.state.watchdogJob = null
        if (rt.state.gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER) {
            rt.projection.syncRuntimeStateStore()
            rt.projection.updateNotificationFromDb(broadcastStats = true)
            return
        }
        if (rt.state.gpsRuntimeState != GpsRuntimeState.LOCKING) {
            rt.projection.syncRuntimeStateStore()
            rt.projection.updateNotificationFromDb(broadcastStats = true)
            return
        }
        if (!rt.locationRequests.applyCurrentLocationRequest("resume_gps")) {
            rt.foreground.failActiveTrackingAndStop(rt.locationRequests.resolveLocationRequestFailureMessage())
            return
        }
        rt.projection.syncRuntimeStateStore()
        rt.projection.updateNotificationFromDb(broadcastStats = true)
    }

    fun transitionGpsState(event: GpsRuntimeEvent, reason: String) {
        val previous = rt.state.gpsRuntimeState
        val next = GpsRuntimeStateMachine.transition(previous, event)
        if (next != previous) {
            GeoVaultCaptureLog.d(TrackingServiceConstants.TAG, "GPS runtime state $previous -> $next event=$event reason=$reason")
            rt.deps.runtimeTelemetry.event(
                name = "gps_state",
                details = "from=$previous to=$next event=$event reason=$reason"
            )
            rt.state.gpsRuntimeState = next
        } else {
            rt.state.gpsRuntimeState = next
        }
    }

}
