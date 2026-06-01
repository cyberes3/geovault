package com.geovault.tracker.positioning
import com.geovault.tracker.tracking.TrackingServiceIntents
import com.geovault.tracker.tracking.TrackingServiceConstants

import com.geovault.tracker.positioning.PointEmissionTrouble


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
import com.geovault.tracker.positioning.ingest.TrackerLocationMotionContext
import com.geovault.tracker.positioning.ingest.TrackerLocationPipeline
import com.geovault.tracker.positioning.ingest.FixIngestMode
import com.geovault.tracker.positioning.ingest.TrackerLocationPipelineInput
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
import com.geovault.tracker.positioning.config.GpsRuntimeEvent
import com.geovault.tracker.positioning.config.GpsRuntimeState
import com.geovault.tracker.positioning.config.GpsRuntimeStateMachine
import com.geovault.tracker.services.QueueUploadConfig
import com.geovault.tracker.services.QueueUploadEngine
import com.geovault.tracker.services.QueueUploadOutcomePolicy
import com.geovault.tracker.services.QueueUploadResult
import com.geovault.tracker.services.QueueUploadScope
import com.geovault.tracker.services.QueueUploadSkipReason
import com.geovault.tracker.services.PointFreshnessTracker
import com.geovault.tracker.services.ProviderHealthController
import com.geovault.tracker.services.ProviderHealthDecision
import com.geovault.tracker.positioning.config.PositioningDensity
import com.geovault.tracker.positioning.config.PositioningPresetValues
import com.geovault.tracker.positioning.config.PositioningPresets
import com.geovault.tracker.services.RecordingRuntimeReducer
import com.geovault.tracker.services.RuntimeAccuracyHoldPolicy
import com.geovault.tracker.services.RuntimeEventPublisher
import com.geovault.tracker.positioning.PositioningContext
import com.geovault.tracker.services.TrackingMotionMode
import com.geovault.tracker.services.TrackingNotificationPresenter
import com.geovault.tracker.positioning.config.PositioningPolicyConfig
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


    internal fun PositioningRuntime.requestStartTracking(path: TrackingServiceIntents.StartupCommandPath, trigger: String): Boolean {
        synchronized(startupStateLock) {
            if (isTracking) {
                GeoVaultCaptureLog.i(TrackingServiceConstants.TAG, "Ignoring start request; tracking already active")
                return true
            }
            if (startupInProgress) {
                GeoVaultCaptureLog.i(TrackingServiceConstants.TAG, "Ignoring start request; startup already in progress")
                return true
            }
            setStartupInProgress(true)
            startupReadyForEvents = false
        }
        transitionControlState(TrackingControlEvent.StartRequested)
        val selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(service)
        if (!TrackingServiceIntents.hasValidSelectedTrackerId(selectedTrackerId)) {
            GeoVaultCaptureLog.w(TrackingServiceConstants.TAG, "Start blocked: invalid selected tracker id")
            setStartupInProgress(false)
            failStartup(
                message = service.getString(R.string.no_tracker_selected_go_to_settings),
                path = path,
                trigger = trigger,
                reason = "invalid_selected_tracker"
            )
            return false
        }
        if (!TrackingPermissionGate.hasRequiredPermissionsForTracking(service)) {
            GeoVaultCaptureLog.w(TrackingServiceConstants.TAG, "Start blocked: required tracking permissions missing")
            setStartupInProgress(false)
            failStartup(
                message = service.getString(R.string.location_permissions_required),
                path = path,
                trigger = trigger,
                reason = "permissions_missing"
            )
            return false
        }
        if (!isGpsProviderEnabled()) {
            GeoVaultCaptureLog.w(TrackingServiceConstants.TAG, "Start blocked: GPS provider disabled")
            setStartupInProgress(false)
            failStartup(
                message = service.getString(R.string.gps_provider_required),
                path = path,
                trigger = trigger,
                reason = "gps_provider_disabled"
            )
            return false
        }
        serviceScope.launch {
            try {
                performStartTracking(trigger = trigger)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                GeoVaultCaptureLog.e(TrackingServiceConstants.TAG, "Start failed during startup pipeline", t)
                failStartup(
                    message = service.getString(R.string.unable_to_start_location_updates),
                    path = path,
                    trigger = trigger,
                    reason = "startup_pipeline_exception"
                )
            } finally {
                setStartupInProgress(false)
            }
        }
        return true
    }

    internal suspend fun PositioningRuntime.performStartTracking(trigger: String) {
        TrackPointBus.pauseLocalDelivery()
        trackingGeneration++
        val runGeneration = trackingGeneration
        val selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(service)
        if (selectedTrackerId.isNotEmpty()) {
            locationIngestCoordinator.resetSession(selectedTrackerId)
        }
        sessionVisibleBoundaryId = withContext(Dispatchers.IO) {
            database.locationDao().getMaxId()
        }
        sessionBoundaryForBacklogId = sessionVisibleBoundaryId
        val sessionStartedAtMs = System.currentTimeMillis()
        pointFreshnessTracker.reset(sessionStartedAtMs = sessionStartedAtMs)
        repeatedOutlierSuppressor.reset()
        providerHealthController.reset()
        lastFixDeliveryAtMs = 0L
        lastLocationRequestAppliedAtMs = 0L
        stationaryFreshnessCoordinator.resetSession()
        uploadLivenessState = UploadLivenessState()
        lastLoggedPointEmissionTrouble = PointEmissionTrouble.None
        lastAccuracyHoldLogKey = null
        lastLocationFilterLogSignature = null
        lastPositioningDiagnosticSnapshotKey = null
        if (selectedTrackerId.isNotEmpty()) {
            restoreLocalFreshnessFromDatabase(
                trackerId = selectedTrackerId,
                sessionStartedAtMs = sessionStartedAtMs,
            )
        }
        isTracking = true
        transitionGpsState(GpsRuntimeEvent.TRACKING_STARTED, "perform_start_tracking")
        transitionControlState(TrackingControlEvent.StartSucceeded)
        startAutoModeTickIfNeeded()
        lastFilteredLocation = null
        latestObservedRawLocation = null
        clearPausedFreshnessProbe(reason = "start_tracking", clearLastFreshnessTimestamp = true)
        lowAccuracyFallbackCandidate = null
        lowAccuracyFallbackCoordinator.onTrackingStopped()
        autoTrackingMotionCoordinator.reset()
        lastAutoModeChangedAtMs = 0L
        lowAccuracyFallbackTimerArmedAtMs = 0L
        lowAccuracyFallbackEmitCountThisSession = 0
        lowAccuracyFallbackArmCountThisSession = 0
        lowAccuracyFallbackCancelCountThisSession = 0
        lowAccuracyFallbackRejectedFixCountThisSession = 0
        lowAccuracyFallbackLastRejectSummaryAtMs = 0L
        lastLowAccuracyFallbackWaitReason = null
        isFastGpsLockWindowActive = false
        isFastGpsLockPriming = false
        resetFastGpsLockSamples()
        fastGpsLockStartCountThisSession = 0
        fastGpsLockStopCountThisSession = 0
        fastGpsLockTimeoutCountThisSession = 0
        fastGpsLockLastSummaryAtMs = 0L
        resetElasticDistanceOverride(reason = "start_tracking", reapplyRequest = false)
        autoTrackingMotionEngine.reset(System.currentTimeMillis())
        autoTrackingMotionCoordinator.reset()
        consecutiveStationaryPoints = 0
        stationaryAnchorLocation = null
        updateRuntimeSnapshot {
            sessionCoordinator.transitionToRunning(
                previous = it,
                nowMs = sessionStartedAtMs,
                sessionVisibleBoundaryId = sessionVisibleBoundaryId
            )
        }

        settingsRepository.setWasTrackingBeforeExit(true)
        TrackingRecoveryCoordinator.markTrackingStarted(service.applicationContext)
        runtimeEventPublisher.publish(
            type = RuntimeServiceEventType.TRACKING_STARTED,
            reason = "start_tracking",
            trigger = TrackingServiceIntents.mapRuntimeTrigger(trigger)
        )
        startRecoveryHeartbeat()
        ensureGpsProviderReceiverRegistered()
        startRetryJob(runGeneration)
        startBacklogUploader(sessionBoundaryForBacklogId, runGeneration)
        startPreflightMonitor(runGeneration)
        syncRuntimeStateStore()

        try {
            startLocationUpdates()
            maybeStartFastGpsLockWindow(measuredAccuracyMeters = null)
            startupReadyForEvents = true
            serviceScope.launch(Dispatchers.IO) {
                pushQueuedLocations(scope = QueueUploadScope.ALL, updateFailureCounters = false)
            }
            updateNotificationFromDb(broadcastStats = true)
            GeoVaultCaptureLog.i(TrackingServiceConstants.TAG, "Tracking session started boundary=$sessionVisibleBoundaryId")
        } catch (e: SecurityException) {
            GeoVaultCaptureLog.e(TrackingServiceConstants.TAG, "Location updates security failure", e)
            failActiveTrackingAndStop(service.getString(R.string.unable_to_start_location_updates))
        } finally {
            TrackPointBus.resumeLocalDelivery()
        }
    }

    internal fun PositioningRuntime.stopTracking(reason: String, failureReason: String? = null) {
        GeoVaultCaptureLog.d(TrackingServiceConstants.TAG, "Stopping tracking reason=$reason wasRunning=$isTracking")
        transitionControlState(TrackingControlEvent.StopRequested, failureReason = failureReason)
        transitionToStoppedState(failureReason = failureReason)
        settingsRepository.clearWasTrackingBeforeExit()
        TrackingRecoveryCoordinator.markIntentionalStop(service.applicationContext, reason = reason)
        transitionControlState(TrackingControlEvent.StopCompleted)
        cleanupServiceResources(reason = reason)
        TrackPointBus.resumeLocalDelivery()
        stopServiceInstance(reason = reason)
    }

    internal fun PositioningRuntime.transitionToStoppedState(failureReason: String?) {
        trackingGeneration++
        isTracking = false
        setStartupInProgress(false)
        startupReadyForEvents = false
        transitionGpsState(GpsRuntimeEvent.TRACKING_STOPPED, "transition_to_stopped_state")
        lastFilteredLocation = null
        latestObservedRawLocation = null
        stationaryFreshnessCoordinator.onStopped(reason = "tracking_stopped")
        clearPausedFreshnessProbe(reason = "tracking_stopped", clearLastFreshnessTimestamp = true)
        lowAccuracyFallbackCandidate = null
        lowAccuracyFallbackCoordinator.onTrackingStopped()
        lastLowAccuracyFallbackWaitReason = null
        repeatedOutlierSuppressor.reset()
        freshnessRecoveryController.reset()
        providerHealthController.reset()
        lastFixDeliveryAtMs = 0L
        lastLocationRequestAppliedAtMs = 0L
        recoveryAnchorState = null
        recoveryAnchorStore.clear()
        stationaryFreshnessCoordinator.clearRegion()
        uploadLivenessState = UploadLivenessState()
        pointFreshnessTracker.reset(sessionStartedAtMs = 0L)
        lastLoggedPointEmissionTrouble = PointEmissionTrouble.None
        lastAccuracyHoldLogKey = null
        lastLocationFilterLogSignature = null
        lastPositioningDiagnosticSnapshotKey = null
        autoTrackingMotionCoordinator.reset()
        stopAutoModeTick()
        stopFastGpsLockWindow(reason = "tracking_stopped")
        resetElasticDistanceOverride(reason = "tracking_stopped", reapplyRequest = false)
        updateRuntimeSnapshot {
            sessionCoordinator.transitionToStopped(
                previous = it,
                failureReason = failureReason
            )
        }
        syncRuntimeStateStore(
            lifecycleStateOverride = TrackingLifecycleState.STOPPED,
            failureReasonOverride = failureReason,
        )
    }

    internal fun PositioningRuntime.cleanupServiceResources(reason: String) {
        GeoVaultCaptureLog.d(TrackingServiceConstants.TAG, "Cleaning service resources reason=$reason")
        stopRecoveryHeartbeat()
        stopRetryJob()
        stopPreflightMonitor()
        stopBacklogUploader()
        stopAutoModeTick()
        stopFastGpsLockWindow(reason = "cleanup")
        unregisterGpsProviderReceiverIfNeeded()
        cancelLowAccuracyFallbackTimer(clearCandidate = false)
        stationaryFreshnessCoordinator.onStopped(reason = "cleanup_$reason")
        clearPausedFreshnessProbe(reason = "cleanup_$reason", clearLastFreshnessTimestamp = true)
        significantMotionBridge?.cancel()
        autoTrackingMotionCoordinator.reset()
        watchdogJob?.cancel()
        watchdogJob = null
        stopLocationUpdates()
        TrackPointBus.resumeLocalDelivery()
        if (startupForegroundPromoted) {
            service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            startupForegroundPromoted = false
        }
    }

    internal fun PositioningRuntime.stopServiceInstance(reason: String) {
        GeoVaultCaptureLog.d(TrackingServiceConstants.TAG, "Stopping service instance reason=$reason")
        service.stopSelf()
    }

    internal fun PositioningRuntime.startLocationUpdates() {
        locationSessionCoordinator.stopSession()
        lastAppliedLocationRequestKey = null
        lastFixDeliveryAtMs = 0L
        val applied = applyCurrentLocationRequest(reason = "start_or_resume")
        if (!applied) throw SecurityException("Unable to apply location request")
        startFixDeliveryWatchdog()
    }

    internal fun PositioningRuntime.stopLocationUpdates() {
        locationRequestReapplyRetryJob?.cancel()
        locationRequestReapplyRetryJob = null
        fixDeliveryWatchdogJob?.cancel()
        fixDeliveryWatchdogJob = null
        lastAppliedLocationRequestKey = null
        lastLocationRequestAppliedAtMs = 0L
        lastFixDeliveryAtMs = 0L
        locationSessionCoordinator.stopSession()
    }

    internal fun PositioningRuntime.setStartupInProgress(value: Boolean) {
        startupInProgress = value
    }

    internal fun PositioningRuntime.isTrackingActiveOrStarting(): Boolean {
        return isTracking || startupInProgress
    }
