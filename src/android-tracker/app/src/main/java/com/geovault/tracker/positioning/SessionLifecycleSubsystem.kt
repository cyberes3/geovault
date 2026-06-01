package com.geovault.tracker.positioning

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
import com.geovault.tracker.positioning.PointEmissionTrouble
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
import com.geovault.tracker.tracking.TrackingServiceIntents
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

internal class SessionLifecycleSubsystem(private val rt: PositioningRuntime) {
    fun requestStartTracking(path: TrackingServiceIntents.StartupCommandPath, trigger: String): Boolean {
        synchronized(rt.state.startupStateLock) {
            if (rt.state.isTracking) {
                GeoVaultCaptureLog.i(TrackingServiceConstants.TAG, "Ignoring start request; tracking already active")
                return true
            }
            if (rt.state.startupInProgress) {
                GeoVaultCaptureLog.i(TrackingServiceConstants.TAG, "Ignoring start request; startup already in progress")
                return true
            }
            rt.lifecycle.setStartupInProgress(true)
            rt.state.startupReadyForEvents = false
        }
        rt.projection.transitionControlState(TrackingControlEvent.StartRequested)
        val selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(rt.ports.service)
        if (!TrackingServiceIntents.hasValidSelectedTrackerId(selectedTrackerId)) {
            GeoVaultCaptureLog.w(TrackingServiceConstants.TAG, "Start blocked: invalid selected tracker id")
            rt.lifecycle.setStartupInProgress(false)
            rt.foreground.failStartup(
                message = rt.ports.service.getString(R.string.no_tracker_selected_go_to_settings),
                path = path,
                trigger = trigger,
                reason = "invalid_selected_tracker"
            )
            return false
        }
        if (!TrackingPermissionGate.hasRequiredPermissionsForTracking(rt.ports.service)) {
            GeoVaultCaptureLog.w(TrackingServiceConstants.TAG, "Start blocked: required tracking permissions missing")
            rt.lifecycle.setStartupInProgress(false)
            rt.foreground.failStartup(
                message = rt.ports.service.getString(R.string.location_permissions_required),
                path = path,
                trigger = trigger,
                reason = "permissions_missing"
            )
            return false
        }
        if (!rt.utilities.isGpsProviderEnabled()) {
            GeoVaultCaptureLog.w(TrackingServiceConstants.TAG, "Start blocked: GPS provider disabled")
            rt.lifecycle.setStartupInProgress(false)
            rt.foreground.failStartup(
                message = rt.ports.service.getString(R.string.gps_provider_required),
                path = path,
                trigger = trigger,
                reason = "gps_provider_disabled"
            )
            return false
        }
        rt.serviceScope.launch {
            try {
                rt.lifecycle.performStartTracking(trigger = trigger)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                GeoVaultCaptureLog.e(TrackingServiceConstants.TAG, "Start failed during startup pipeline", t)
                rt.foreground.failStartup(
                    message = rt.ports.service.getString(R.string.unable_to_start_location_updates),
                    path = path,
                    trigger = trigger,
                    reason = "startup_pipeline_exception"
                )
            } finally {
                rt.lifecycle.setStartupInProgress(false)
            }
        }
        return true
    }

    suspend fun performStartTracking(trigger: String) {
        TrackPointBus.pauseLocalDelivery()
        rt.state.trackingGeneration++
        val runGeneration = rt.state.trackingGeneration
        val selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(rt.ports.service)
        if (selectedTrackerId.isNotEmpty()) {
            rt.deps.locationIngestCoordinator.resetSession(selectedTrackerId)
        }
        rt.state.sessionVisibleBoundaryId = withContext(Dispatchers.IO) {
            rt.deps.database.locationDao().getMaxId()
        }
        rt.state.sessionBoundaryForBacklogId = rt.state.sessionVisibleBoundaryId
        val sessionStartedAtMs = System.currentTimeMillis()
        rt.deps.pointFreshnessTracker.reset(sessionStartedAtMs = sessionStartedAtMs)
        rt.deps.repeatedOutlierSuppressor.reset()
        rt.deps.providerHealthController.reset()
        rt.deps.stationaryFreshnessCoordinator.resetSession()
        rt.state.resetForStart()
        if (selectedTrackerId.isNotEmpty()) {
            rt.projection.restoreLocalFreshnessFromDatabase(
                trackerId = selectedTrackerId,
                sessionStartedAtMs = sessionStartedAtMs,
            )
        }
        rt.state.isTracking = true
        rt.collection.transitionGpsState(GpsRuntimeEvent.TRACKING_STARTED, "perform_start_tracking")
        rt.projection.transitionControlState(TrackingControlEvent.StartSucceeded)
        rt.motion.startAutoModeTickIfNeeded()
        rt.recovery.pausedFreshness.clearPausedFreshnessProbe(reason = "start_tracking", clearLastFreshnessTimestamp = true)
        rt.deps.lowAccuracyFallbackCoordinator.onTrackingStopped()
        rt.recovery.fastLock.resetFastGpsLockSamples()
        rt.motion.resetElasticDistanceOverride(reason = "start_tracking", reapplyRequest = false)
        rt.deps.autoTrackingMotionEngine.reset(System.currentTimeMillis())
        rt.deps.autoTrackingMotionCoordinator.reset()
        rt.projection.updateRuntimeSnapshot {
            rt.deps.sessionCoordinator.transitionToRunning(
                previous = it,
                nowMs = sessionStartedAtMs,
                sessionVisibleBoundaryId = rt.state.sessionVisibleBoundaryId
            )
        }

        rt.deps.settingsRepository.setWasTrackingBeforeExit(true)
        TrackingRecoveryCoordinator.markTrackingStarted(rt.ports.service.applicationContext)
        rt.deps.runtimeEventPublisher.publish(
            type = RuntimeServiceEventType.TRACKING_STARTED,
            reason = "start_tracking",
            trigger = TrackingServiceIntents.mapRuntimeTrigger(trigger)
        )
        rt.recovery.jobs.startRecoveryHeartbeat()
        rt.collection.ensureGpsProviderReceiverRegistered()
        rt.upload.startRetryJob(runGeneration)
        rt.upload.startBacklogUploader(rt.state.sessionBoundaryForBacklogId, runGeneration)
        rt.upload.startPreflightMonitor(runGeneration)
        rt.projection.syncRuntimeStateStore()

        try {
            rt.lifecycle.startLocationUpdates()
            rt.recovery.fastLock.maybeStartFastGpsLockWindow(measuredAccuracyMeters = null)
            rt.state.startupReadyForEvents = true
            rt.serviceScope.launch(Dispatchers.IO) {
                rt.upload.pushQueuedLocations(scope = QueueUploadScope.ALL, updateFailureCounters = false)
            }
            rt.projection.updateNotificationFromDb(broadcastStats = true)
            GeoVaultCaptureLog.i(TrackingServiceConstants.TAG, "Tracking session started boundary=${rt.state.sessionVisibleBoundaryId}")
        } catch (e: SecurityException) {
            GeoVaultCaptureLog.e(TrackingServiceConstants.TAG, "Location updates security failure", e)
            rt.foreground.failActiveTrackingAndStop(rt.ports.service.getString(R.string.unable_to_start_location_updates))
        } finally {
            TrackPointBus.resumeLocalDelivery()
        }
    }

    fun stopTracking(reason: String, failureReason: String? = null) {
        GeoVaultCaptureLog.d(TrackingServiceConstants.TAG, "Stopping tracking reason=$reason wasRunning=${rt.state.isTracking}")
        rt.projection.transitionControlState(TrackingControlEvent.StopRequested, failureReason = failureReason)
        rt.lifecycle.transitionToStoppedState(failureReason = failureReason)
        rt.deps.settingsRepository.clearWasTrackingBeforeExit()
        TrackingRecoveryCoordinator.markIntentionalStop(rt.ports.service.applicationContext, reason = reason)
        rt.projection.transitionControlState(TrackingControlEvent.StopCompleted)
        rt.lifecycle.cleanupServiceResources(reason = reason)
        TrackPointBus.resumeLocalDelivery()
        rt.lifecycle.stopServiceInstance(reason = reason)
    }

    fun transitionToStoppedState(failureReason: String?) {
        rt.state.trackingGeneration++
        rt.state.isTracking = false
        rt.lifecycle.setStartupInProgress(false)
        rt.state.startupReadyForEvents = false
        rt.collection.transitionGpsState(GpsRuntimeEvent.TRACKING_STOPPED, "transition_to_stopped_state")
        rt.deps.stationaryFreshnessCoordinator.onStopped(reason = "tracking_stopped")
        rt.recovery.pausedFreshness.clearPausedFreshnessProbe(reason = "tracking_stopped", clearLastFreshnessTimestamp = true)
        rt.deps.lowAccuracyFallbackCoordinator.onTrackingStopped()
        rt.deps.repeatedOutlierSuppressor.reset()
        rt.deps.freshnessRecoveryController.reset()
        rt.deps.providerHealthController.reset()
        rt.deps.recoveryAnchorStore.clear()
        rt.deps.stationaryFreshnessCoordinator.clearRegion()
        rt.deps.pointFreshnessTracker.reset(sessionStartedAtMs = 0L)
        rt.state.resetForStop()
        rt.deps.autoTrackingMotionCoordinator.reset()
        rt.motion.stopAutoModeTick()
        rt.recovery.fastLock.stopFastGpsLockWindow(reason = "tracking_stopped")
        rt.motion.resetElasticDistanceOverride(reason = "tracking_stopped", reapplyRequest = false)
        rt.projection.updateRuntimeSnapshot {
            rt.deps.sessionCoordinator.transitionToStopped(
                previous = it,
                failureReason = failureReason
            )
        }
        rt.projection.syncRuntimeStateStore(
            lifecycleStateOverride = TrackingLifecycleState.STOPPED,
            failureReasonOverride = failureReason,
        )
    }

    fun cleanupServiceResources(reason: String) {
        GeoVaultCaptureLog.d(TrackingServiceConstants.TAG, "Cleaning rt.ports.service resources reason=$reason")
        rt.recovery.jobs.stopRecoveryHeartbeat()
        rt.upload.stopRetryJob()
        rt.upload.stopPreflightMonitor()
        rt.upload.stopBacklogUploader()
        rt.motion.stopAutoModeTick()
        rt.recovery.fastLock.stopFastGpsLockWindow(reason = "cleanup")
        rt.collection.unregisterGpsProviderReceiverIfNeeded()
        rt.recovery.fallback.cancelLowAccuracyFallbackTimer(clearCandidate = false)
        rt.deps.stationaryFreshnessCoordinator.onStopped(reason = "cleanup_$reason")
        rt.recovery.pausedFreshness.clearPausedFreshnessProbe(reason = "cleanup_$reason", clearLastFreshnessTimestamp = true)
        rt.deps.significantMotionBridge?.cancel()
        rt.deps.autoTrackingMotionCoordinator.reset()
        rt.state.watchdogJob?.cancel()
        rt.state.watchdogJob = null
        rt.lifecycle.stopLocationUpdates()
        TrackPointBus.resumeLocalDelivery()
        if (rt.state.startupForegroundPromoted) {
            rt.ports.service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            rt.state.startupForegroundPromoted = false
        }
    }

    fun stopServiceInstance(reason: String) {
        GeoVaultCaptureLog.d(TrackingServiceConstants.TAG, "Stopping rt.ports.service instance reason=$reason")
        rt.ports.service.stopSelf()
    }

    fun startLocationUpdates() {
        rt.deps.locationSessionCoordinator.stopSession()
        rt.state.lastAppliedLocationRequestKey = null
        rt.state.lastFixDeliveryAtMs = 0L
        val applied = rt.locationRequests.applyCurrentLocationRequest(reason = "start_or_resume")
        if (!applied) throw SecurityException("Unable to apply location request")
        rt.locationRequests.startFixDeliveryWatchdog()
    }

    fun stopLocationUpdates() {
        rt.state.locationRequestReapplyRetryJob?.cancel()
        rt.state.locationRequestReapplyRetryJob = null
        rt.state.fixDeliveryWatchdogJob?.cancel()
        rt.state.fixDeliveryWatchdogJob = null
        rt.state.lastAppliedLocationRequestKey = null
        rt.state.lastLocationRequestAppliedAtMs = 0L
        rt.state.lastFixDeliveryAtMs = 0L
        rt.deps.locationSessionCoordinator.stopSession()
    }

    fun setStartupInProgress(value: Boolean) {
        rt.state.startupInProgress = value
    }

    fun isTrackingActiveOrStarting(): Boolean {
        return rt.state.isTracking || rt.state.startupInProgress
    }

}
