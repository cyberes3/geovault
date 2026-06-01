package com.geovault.tracker.tracking


import android.app.ForegroundServiceStartNotAllowedException
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.Service
import android.Manifest
import android.os.VibrationEffect
import android.os.VibratorManager
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
import android.provider.Settings
import com.geovault.common.logging.GeoVaultCaptureLog
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.RetrofitClient
import com.geovault.tracker.db.AppDatabase
import com.geovault.tracker.R
import com.geovault.tracker.SelectedTrackerManager
import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.TrackingLocationPolicy
import com.geovault.tracker.AutoMotionStabilityPolicy
import com.geovault.tracker.TrackingRecoveryCoordinator
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.location.AutoTrackingMotionEngine
import com.geovault.tracker.location.AutoTrackingMotionState
import com.geovault.tracker.location.AutoTrackingEngineOutput
import com.geovault.tracker.location.AutoMotionRejectHandling
import com.geovault.tracker.location.AutoTrackingMotionCoordinator
import com.geovault.tracker.location.AutoTrackingMotionEvidenceGate
import com.geovault.tracker.location.LowAccuracyFallbackCoordinator
import com.geovault.tracker.location.LowAccuracyFallbackArmDecision
import com.geovault.tracker.location.LowAccuracyFallbackLoopDecision
import com.geovault.tracker.location.NetworkStatusMonitor
import com.geovault.tracker.location.PausedFreshnessDecision
import com.geovault.tracker.location.PausedFreshnessDecisionReason
import com.geovault.tracker.location.PausedFreshnessPointFactory
import com.geovault.tracker.location.PausedFreshnessPolicy
import com.geovault.tracker.location.FreshnessRecoveryController
import com.geovault.tracker.location.FreshnessRecoveryDecision
import com.geovault.tracker.location.TrackerLocationMotionContext
import com.geovault.tracker.location.TrackerLocationPipeline
import com.geovault.tracker.location.FixIngestMode
import com.geovault.tracker.location.TrackerLocationPipelineInput
import com.geovault.tracker.location.PositioningRecoveryConfig
import com.geovault.tracker.location.RepeatedOutlierSuppressor
import com.geovault.tracker.location.RecoveryAnchorState
import com.geovault.tracker.location.RecoveryAnchorStore
import com.geovault.tracker.location.StationaryRegionStore
import com.geovault.tracker.location.StationaryFreshnessActions
import com.geovault.tracker.location.StationaryFreshnessCoordinator
import com.geovault.tracker.location.StationaryPingActions
import com.geovault.tracker.location.StationaryPingController
import com.geovault.tracker.location.StationaryPauseEligibilityPolicy
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
import com.geovault.tracker.runtime.RuntimeTelemetry
import com.geovault.tracker.runtime.RuntimeServiceEventType
import com.geovault.tracker.runtime.RuntimeTrigger
import com.geovault.tracker.runtime.PositioningDiagnosticEvent
import com.geovault.tracker.runtime.PositioningDiagnosticSnapshot
import com.geovault.tracker.runtime.TrackingServiceLifecycleGate
import com.geovault.tracker.runtime.TrackingRuntimeController
import com.geovault.tracker.sensor.SensorManagerSignificantMotionTrigger
import com.geovault.tracker.sensor.SignificantMotionResumeBridge
import com.geovault.tracker.services.LocationIngestCoordinator
import com.geovault.tracker.services.LocationIngestResult
import com.geovault.tracker.services.LocationSessionCoordinator
import com.geovault.tracker.services.GpsRuntimeEvent
import com.geovault.tracker.services.GpsRuntimeState
import com.geovault.tracker.services.GpsRuntimeStateMachine
import com.geovault.tracker.services.QueueUploadConfig
import com.geovault.tracker.services.QueueUploadEngine
import com.geovault.tracker.services.QueueUploadOutcomePolicy
import com.geovault.tracker.services.QueueUploadResult
import com.geovault.tracker.services.QueueUploadScope
import com.geovault.tracker.services.QueueUploadSkipReason
import com.geovault.tracker.services.PointFreshnessTracker
import com.geovault.tracker.services.ProviderHealthController
import com.geovault.tracker.services.ProviderHealthDecision
import com.geovault.tracker.services.PositioningDensity
import com.geovault.tracker.services.PositioningPresetValues
import com.geovault.tracker.services.PositioningPresets
import com.geovault.tracker.services.RecordingRuntimeReducer
import com.geovault.tracker.services.RuntimeAccuracyHoldPolicy
import com.geovault.tracker.services.RuntimeEventPublisher
import com.geovault.tracker.services.TrackerPositioningRuntimeContext
import com.geovault.tracker.services.TrackingMotionMode
import com.geovault.tracker.services.TrackingNotificationPresenter
import com.geovault.tracker.services.PositioningPolicyConfig
import com.geovault.tracker.services.TrackingRuntimeOrchestrator
import com.geovault.tracker.services.RuntimeLocationGateInput
import com.geovault.tracker.services.FastLockTriggerInput
import com.geovault.tracker.services.TrackingSessionCoordinator
import com.geovault.tracker.services.TrackingStatusAccuracyInput
import com.geovault.tracker.services.TrackingStatusAccuracyProjector
import com.geovault.tracker.services.TrackingRuntimeStateStore
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import com.geovault.tracker.services.RuntimeSnapshotProjector
import com.geovault.tracker.services.RuntimeSnapshotProjectionInput
import com.geovault.tracker.services.UploadLivenessState
import com.geovault.tracker.settings.TrackerSettings
import com.geovault.tracker.settings.TrackerSettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import kotlin.random.Random


    internal fun TrackingServiceHost.enterStationaryRegion(
        anchorLocation: Location,
        nowMs: Long,
        motionMode: TrackingMotionMode,
        radiusMeters: Float,
    ) {
        val trackerId = SelectedTrackerPrefs.selectedTrackerId(service)
        if (trackerId.isBlank()) return
        val anchor = RecoveryAnchorState.fromLocation(
            trackerId = trackerId,
            sessionBoundaryId = sessionVisibleBoundaryId,
            location = anchorLocation,
            radiusMeters = radiusMeters,
            source = "stationary_region",
            motionMode = motionMode,
        )
        stationaryFreshnessCoordinator.enterRegion(anchor = anchor, nowMs = nowMs)
        recoveryAnchorState = anchor
        recoveryAnchorStore.save(anchor)
    }

    internal fun TrackingServiceHost.ensureGpsProviderReceiverRegistered() {
        if (gpsProviderReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(
            service,
            gpsProviderReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        gpsProviderReceiverRegistered = true
    }

    internal fun TrackingServiceHost.unregisterGpsProviderReceiverIfNeeded() {
        if (!gpsProviderReceiverRegistered) return
        runCatching { service.unregisterReceiver(gpsProviderReceiver) }
        gpsProviderReceiverRegistered = false
    }

    internal fun TrackingServiceHost.enterWaitingForGpsProvider(reason: String) {
        if (
            !isTracking ||
            gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER ||
            gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
        ) {
            return
        }
        transitionGpsState(GpsRuntimeEvent.PROVIDER_DISABLED, reason)
        if (gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED) {
            stationaryFreshnessCoordinator.onProviderPaused(reason = reason)
        } else {
            stationaryFreshnessCoordinator.onResumed(reason = "provider_disabled")
        }
        resetElasticDistanceOverride(reason = "gps_provider_disabled", reapplyRequest = false)
        stopFastGpsLockWindow(reason = "gps_provider_disabled")
        cancelLowAccuracyFallbackTimer(clearCandidate = false)
        clearPausedFreshnessProbe(reason = "gps_provider_disabled")
        stopLocationUpdates()
        GeoVaultCaptureLog.w(TrackingServiceConstants.TAG, "GPS provider disabled while tracking reason=$reason")
        runtimeTelemetry.event("gps_provider_disabled", "reason=$reason")
        syncRuntimeStateStore()
        updateNotificationFromDb(broadcastStats = true)
    }

    internal fun TrackingServiceHost.resumeFromGpsProviderWait(reason: String) {
        if (
            !isTracking ||
            (gpsRuntimeState != GpsRuntimeState.WAITING_FOR_PROVIDER &&
                gpsRuntimeState != GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED)
        ) {
            return
        }
        transitionGpsState(GpsRuntimeEvent.PROVIDER_ENABLED, reason)
        if (gpsRuntimeState == GpsRuntimeState.PAUSED_FOR_MOTION) {
            stationaryFreshnessCoordinator.onProviderRestored(reason = reason)
            if (gpsRuntimeState != GpsRuntimeState.PAUSED_FOR_MOTION) {
                return
            }
            GeoVaultCaptureLog.i(TrackingServiceConstants.TAG, "GPS provider re-enabled while paused reason=$reason")
            syncRuntimeStateStore()
            updateNotificationFromDb(broadcastStats = true)
            return
        }
        if (isWaitingForProviderState()) {
            syncRuntimeStateStore()
            updateNotificationFromDb(broadcastStats = true)
            return
        }
        if (!applyCurrentLocationRequest("gps_provider_reenabled_$reason")) {
            failActiveTrackingAndStop(resolveLocationRequestFailureMessage())
            return
        }
        GeoVaultCaptureLog.i(TrackingServiceConstants.TAG, "GPS provider re-enabled, resumed updates reason=$reason")
        syncRuntimeStateStore()
        updateNotificationFromDb(broadcastStats = true)
    }

    internal fun TrackingServiceHost.pauseGps() {
        pauseGpsInternal(force = false)
    }

    internal fun TrackingServiceHost.pauseGpsInternal(force: Boolean) {
        if (!isTracking) return
        if (
            !force &&
            (gpsRuntimeState == GpsRuntimeState.PAUSED_FOR_MOTION ||
                gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED)
        ) {
            return
        }
        if (significantMotionBridge?.isAvailable() != true) {
            runtimeTelemetry.event("gps_pause_skipped", "reason=significant_motion_unavailable")
            return
        }
        transitionGpsState(GpsRuntimeEvent.PAUSE_FOR_MOTION, "pause_for_motion")
        transitionControlState(TrackingControlEvent.PauseRequested)
        resetElasticDistanceOverride(reason = "gps_paused", reapplyRequest = false)
        stopFastGpsLockWindow(reason = "gps_paused")
        stopLocationUpdates()
        autoTrackingMotionEngine.onGpsPaused(System.currentTimeMillis())
        autoTrackingMotionCoordinator.clearEvidenceCandidate()
        significantMotionBridge?.request()
        sigMotionSensorStartTime = System.currentTimeMillis()
        startSensorWatchdog()
        stationaryFreshnessCoordinator.schedulePausedPing(
            reason = "pause_for_motion",
            providerAvailable = isGpsProviderEnabled()
        )
        syncRuntimeStateStore()
        updateNotificationFromDb(broadcastStats = true)
    }

    internal fun TrackingServiceHost.startSensorWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            while (
                gpsRuntimeState == GpsRuntimeState.PAUSED_FOR_MOTION ||
                gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
            ) {
                delay(60_000L)
                requestStationaryFreshnessProbeIfDue(reason = "sensor_watchdog")
                val age = System.currentTimeMillis() - sigMotionSensorStartTime
                if (age > 5 * 60_000L) {
                    significantMotionBridge?.cancel()
                    significantMotionBridge?.request()
                    sigMotionSensorStartTime = System.currentTimeMillis()
                    runtimeTelemetry.event("sensor_watchdog_refresh", "ageMs=$age")
                }
            }
        }
    }

    internal fun TrackingServiceHost.requestStationaryFreshnessProbeIfDue(reason: String) {
        if (
            !isTracking ||
            (gpsRuntimeState != GpsRuntimeState.PAUSED_FOR_MOTION &&
                gpsRuntimeState != GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED)
        ) {
            return
        }
        val dueAtMs = stationaryFreshnessCoordinator.nextFreshnessDueAtMs(
            intervalMs = currentPositioningRuntimeContext().stationaryProbeIntervalMs
        ) ?: return
        val nowMs = System.currentTimeMillis()
        if (nowMs < dueAtMs) return
        runtimeTelemetry.event(
            "stationary_ping_due_reconciled",
            "reason=$reason overdueMs=${nowMs - dueAtMs} state=$gpsRuntimeState"
        )
        requestStationaryFreshnessProbe(reason = reason)
    }

    internal fun TrackingServiceHost.resumeGps(reason: String = "significant_motion_resume") {
        if (
            !isTracking ||
            (gpsRuntimeState != GpsRuntimeState.PAUSED_FOR_MOTION &&
                gpsRuntimeState != GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED)
        ) {
            return
        }
        transitionGpsState(GpsRuntimeEvent.RESUME_FROM_MOTION, reason)
        transitionControlState(TrackingControlEvent.ResumeRequested)
        // Mark the next fix as a resume boundary. Real movement should be
        // accepted, but a false motion wakeup while stationary should still
        // be able to snap back to the pre-pause anchor.
        SelectedTrackerPrefs.selectedTrackerId(service).takeIf { it.isNotBlank() }?.let { trackerId ->
            TrackPointPolicyEngine.notifyMotionChanged(
                source = TrackPointSource.LOCAL_GPS,
                trackId = trackerId,
            )
        }
        resetElasticDistanceOverride(reason = "gps_resumed", reapplyRequest = false)
        stopFastGpsLockWindow(reason = "gps_resumed")
        if (lowAccuracyFallbackCoordinator.hasPendingCandidate()) {
            ensureLowAccuracyFallbackTimerRunning()
            runtimeTelemetry.event("fallback_preserved_on_resume", "reason=$reason")
        }
        stationaryFreshnessCoordinator.onResumed(reason = reason)
        consecutiveStationaryPoints = 0
        stationaryAnchorLocation = null
        stationaryFreshnessCoordinator.clearRegion()
        autoTrackingMotionEngine.onGpsResumed(System.currentTimeMillis())
        watchdogJob?.cancel()
        watchdogJob = null
        if (gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER) {
            syncRuntimeStateStore()
            updateNotificationFromDb(broadcastStats = true)
            return
        }
        if (gpsRuntimeState != GpsRuntimeState.LOCKING) {
            syncRuntimeStateStore()
            updateNotificationFromDb(broadcastStats = true)
            return
        }
        if (!applyCurrentLocationRequest("resume_gps")) {
            failActiveTrackingAndStop(resolveLocationRequestFailureMessage())
            return
        }
        syncRuntimeStateStore()
        updateNotificationFromDb(broadcastStats = true)
    }

    internal fun TrackingServiceHost.transitionGpsState(event: GpsRuntimeEvent, reason: String) {
        val previous = gpsRuntimeState
        val next = GpsRuntimeStateMachine.transition(previous, event)
        if (next != previous) {
            GeoVaultCaptureLog.d(TrackingServiceConstants.TAG, "GPS runtime state $previous -> $next event=$event reason=$reason")
            runtimeTelemetry.event(
                name = "gps_state",
                details = "from=$previous to=$next event=$event reason=$reason"
            )
        }
        gpsRuntimeState = next
    }
