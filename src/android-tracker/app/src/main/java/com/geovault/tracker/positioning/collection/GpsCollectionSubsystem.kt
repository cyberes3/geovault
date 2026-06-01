package com.geovault.tracker.positioning.collection

import com.geovault.tracker.positioning.PositioningRuntime
import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.os.UserManager
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.RetrofitClient
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.AutoMotionStabilityPolicy
import com.geovault.tracker.R
import com.geovault.tracker.SelectedTrackerManager
import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.TrackingLocationPolicy
import com.geovault.tracker.TrackingRecoveryCoordinator
import com.geovault.tracker.db.AppDatabase
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.location.AutoMotionRejectHandling
import com.geovault.tracker.location.AutoTrackingEngineOutput
import com.geovault.tracker.location.AutoTrackingMotionCoordinator
import com.geovault.tracker.location.AutoTrackingMotionEngine
import com.geovault.tracker.location.AutoTrackingMotionEvidenceGate
import com.geovault.tracker.location.AutoTrackingMotionState
import com.geovault.tracker.location.FreshnessRecoveryController
import com.geovault.tracker.location.FreshnessRecoveryDecision
import com.geovault.tracker.location.LowAccuracyFallbackArmDecision
import com.geovault.tracker.location.LowAccuracyFallbackCoordinator
import com.geovault.tracker.location.LowAccuracyFallbackLoopDecision
import com.geovault.tracker.location.NetworkStatusMonitor
import com.geovault.tracker.location.PausedFreshnessDecision
import com.geovault.tracker.location.PausedFreshnessDecisionReason
import com.geovault.tracker.location.PausedFreshnessPointFactory
import com.geovault.tracker.location.PausedFreshnessPolicy
import com.geovault.tracker.location.PositioningRecoveryConfig
import com.geovault.tracker.location.RecoveryAnchorState
import com.geovault.tracker.location.RecoveryAnchorStore
import com.geovault.tracker.location.RepeatedOutlierSuppressor
import com.geovault.tracker.location.StationaryFreshnessActions
import com.geovault.tracker.location.StationaryFreshnessCoordinator
import com.geovault.tracker.location.StationaryPauseEligibilityPolicy
import com.geovault.tracker.location.StationaryPingActions
import com.geovault.tracker.location.StationaryPingController
import com.geovault.tracker.location.StationaryRegionStore
import com.geovault.tracker.location.SyncFailureClass
import com.geovault.tracker.location.TrackingControlEvent
import com.geovault.tracker.location.TrackingControlPlane
import com.geovault.tracker.location.TrackingControlState
import com.geovault.tracker.location.TrackingLifecycleState
import com.geovault.tracker.location.TrackingLocationRequestInput
import com.geovault.tracker.location.TrackingLocationRequestPolicy
import com.geovault.tracker.location.TrackingPermissionGate
import com.geovault.tracker.location.TrackingSyncPolicy
import com.geovault.tracker.policy.CanonicalTimeNormalizer
import com.geovault.tracker.policy.TrackPointBus
import com.geovault.tracker.policy.TrackPointEmissionDecision
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointPolicyEngine
import com.geovault.tracker.policy.TrackPointQuality
import com.geovault.tracker.policy.TrackPointRejectReason
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.positioning.PositioningContext
import com.geovault.tracker.positioning.config.GpsRuntimeEvent
import com.geovault.tracker.positioning.config.GpsRuntimeState
import com.geovault.tracker.positioning.config.GpsRuntimeStateMachine
import com.geovault.tracker.positioning.config.PositioningDensity
import com.geovault.tracker.positioning.config.PositioningPolicyConfig
import com.geovault.tracker.positioning.config.PositioningPresetValues
import com.geovault.tracker.positioning.config.PositioningPresets
import com.geovault.tracker.positioning.ingest.FixIngestMode
import com.geovault.tracker.positioning.ingest.TrackerLocationMotionContext
import com.geovault.tracker.positioning.ingest.TrackerLocationPipeline
import com.geovault.tracker.positioning.ingest.TrackerLocationPipelineInput
import com.geovault.tracker.runtime.PositioningDiagnosticEvent
import com.geovault.tracker.runtime.PositioningDiagnosticSnapshot
import com.geovault.tracker.runtime.RuntimeServiceEventType
import com.geovault.tracker.runtime.RuntimeTelemetry
import com.geovault.tracker.runtime.RuntimeTrigger
import com.geovault.tracker.runtime.TrackingRuntimeController
import com.geovault.tracker.runtime.TrackingServiceLifecycleGate
import com.geovault.tracker.sensor.SensorManagerSignificantMotionTrigger
import com.geovault.tracker.sensor.SignificantMotionResumeBridge
import com.geovault.tracker.services.FastLockTriggerInput
import com.geovault.tracker.services.LocationIngestCoordinator
import com.geovault.tracker.services.LocationIngestResult
import com.geovault.tracker.services.LocationSessionCoordinator
import com.geovault.tracker.services.PointFreshnessTracker
import com.geovault.tracker.services.ProviderHealthController
import com.geovault.tracker.services.ProviderHealthDecision
import com.geovault.tracker.services.QueueUploadConfig
import com.geovault.tracker.services.QueueUploadEngine
import com.geovault.tracker.services.QueueUploadOutcomePolicy
import com.geovault.tracker.services.QueueUploadResult
import com.geovault.tracker.services.QueueUploadScope
import com.geovault.tracker.services.QueueUploadSkipReason
import com.geovault.tracker.services.RecordingRuntimeReducer
import com.geovault.tracker.services.RuntimeAccuracyHoldPolicy
import com.geovault.tracker.services.RuntimeEventPublisher
import com.geovault.tracker.services.RuntimeLocationGateInput
import com.geovault.tracker.services.RuntimeSnapshotProjectionInput
import com.geovault.tracker.services.RuntimeSnapshotProjector
import com.geovault.tracker.services.TrackingMotionMode
import com.geovault.tracker.services.TrackingNotificationPresenter
import com.geovault.tracker.services.TrackingRuntimeOrchestrator
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import com.geovault.tracker.services.TrackingRuntimeStateStore
import com.geovault.tracker.services.TrackingSessionCoordinator
import com.geovault.tracker.services.TrackingStatusAccuracyInput
import com.geovault.tracker.services.TrackingStatusAccuracyProjector
import com.geovault.tracker.services.UploadLivenessState
import com.geovault.tracker.settings.TrackerSettings
import com.geovault.tracker.settings.TrackerSettingsRepository
import com.geovault.tracker.tracking.TrackingServiceConstants
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject

internal class GpsCollectionSubsystem(private val rt: PositioningRuntime) {
    fun enterStationaryRegion(
        anchorLocation: Location,
        nowMs: Long,
        motionMode: TrackingMotionMode,
        radiusMeters: Float,
    ) {
        val trackerId = SelectedTrackerPrefs.selectedTrackerId(rt.ports.service)
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
        rt.deps.autoTrackingMotionEngine.onGpsPaused(System.currentTimeMillis())
        rt.deps.autoTrackingMotionCoordinator.clearEvidenceCandidate()
        rt.deps.significantMotionBridge?.request()
        rt.state.sigMotionSensorStartTime = System.currentTimeMillis()
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
                val age = System.currentTimeMillis() - rt.state.sigMotionSensorStartTime
                if (age > 5 * 60_000L) {
                    rt.deps.significantMotionBridge?.cancel()
                    rt.deps.significantMotionBridge?.request()
                    rt.state.sigMotionSensorStartTime = System.currentTimeMillis()
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
        val nowMs = System.currentTimeMillis()
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
        SelectedTrackerPrefs.selectedTrackerId(rt.ports.service).takeIf { it.isNotBlank() }?.let { trackerId ->
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
        rt.deps.autoTrackingMotionEngine.onGpsResumed(System.currentTimeMillis())
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
        }
        rt.state.gpsRuntimeState = next
    }

}
