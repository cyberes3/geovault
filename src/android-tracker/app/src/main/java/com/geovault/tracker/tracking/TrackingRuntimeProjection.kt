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


    internal fun TrackingServiceHost.updateNotificationFromDb(broadcastStats: Boolean) {
        serviceScope.launch(Dispatchers.IO) {
            val count = if (isTracking) {
                database.locationDao().getCurrentSessionCountForTracker(
                    trackerId = SelectedTrackerPrefs.selectedTrackerId(service),
                    sessionBoundaryId = sessionVisibleBoundaryId
                )
            } else {
                0
            }
            updateRuntimeSnapshot { it.copy(queuedPointsVisible = count) }
            withContext(Dispatchers.Main) {
                syncRuntimeStateStore()
                if (startupForegroundPromoted) {
                    notificationPresenter.updateForegroundNotification(runtimeSnapshot)
                }
            }
            if (broadcastStats) {
                broadcastSessionStats()
            }
        }
    }

    internal fun TrackingServiceHost.broadcastSessionStats() {
        if (isTracking && !startupReadyForEvents) return
        service.sendBroadcast(Intent(TrackingServiceConstants.SESSION_STATS_UPDATE).apply { setPackage(service.packageName) })
    }

    internal fun TrackingServiceHost.updateRuntimeSnapshot(
        transform: (TrackingRuntimeSnapshot) -> TrackingRuntimeSnapshot
    ): TrackingRuntimeSnapshot {
        return synchronized(runtimeSnapshotLock) {
            runtimeSnapshot = transform(runtimeSnapshot)
            runtimeSnapshot
        }
    }

    internal fun TrackingServiceHost.applyAccuracyHoldUpdate(
        incomingAccuracyMeters: Float?,
        pointEmissionTrouble: PointEmissionTrouble = PointEmissionTrouble.None,
        extraTransform: ((TrackingRuntimeSnapshot) -> TrackingRuntimeSnapshot)? = null,
    ): TrackingRuntimeSnapshot {
        val threshold = currentPositioningRuntimeContext().effectiveAccuracyThresholdMeters
        val nowElapsedMs = SystemClock.elapsedRealtime()
        return updateRuntimeSnapshot { snapshot ->
            val decision = RuntimeAccuracyHoldPolicy.next(
                previous = snapshot,
                incomingAccuracyMeters = incomingAccuracyMeters,
                effectiveAccuracyThresholdMeters = threshold,
                nowElapsedMs = nowElapsedMs,
                forceCurrentAccuracy = pointEmissionTrouble.active,
            )
            val lastGoodAgeMs = decision.lastGoodAccuracyAtElapsedMs
                .takeIf { it > 0L }
                ?.let { nowElapsedMs - it }
            val accuracyHoldLogKey = buildAccuracyHoldLogKey(
                incomingAccuracyMeters = incomingAccuracyMeters,
                displayedAccuracyMeters = decision.displayedAccuracyMeters,
                held = decision.heldLastGoodAccuracy,
                pointEmissionTrouble = pointEmissionTrouble,
            )
            if (accuracyHoldLogKey != lastAccuracyHoldLogKey) {
                lastAccuracyHoldLogKey = accuracyHoldLogKey
                runtimeTelemetry.decision(
                    name = "accuracy_hold",
                    details = "raw=${incomingAccuracyMeters ?: -1f} displayed=${decision.displayedAccuracyMeters ?: -1f} " +
                        "threshold=$threshold held=${decision.heldLastGoodAccuracy} " +
                        "forceCurrent=${pointEmissionTrouble.active} " +
                        "troubleReason=${pointEmissionTrouble.reason ?: "none"} " +
                        "accuracyBlocked=${pointEmissionTrouble.accuracyBlocked} " +
                        "lastGood=${decision.lastGoodAccuracyMeters ?: -1f} lastGoodAgeMs=${lastGoodAgeMs ?: -1L} " +
                        "graceMs=${RuntimeAccuracyHoldPolicy.ACCURACY_HOLD_GRACE_MS}"
                )
            }
            logPointEmissionTroubleTransition(
                previous = lastLoggedPointEmissionTrouble,
                current = pointEmissionTrouble,
                nowMs = System.currentTimeMillis(),
            )
            lastLoggedPointEmissionTrouble = pointEmissionTrouble
            val withAccuracy = snapshot.copy(
                lastAccuracyMeters = decision.displayedAccuracyMeters,
                lastGoodAccuracyMeters = decision.lastGoodAccuracyMeters,
                lastGoodAccuracyAtElapsedMs = decision.lastGoodAccuracyAtElapsedMs,
                currentFixAccuracyMeters = incomingAccuracyMeters,
                activePointEmissionTrouble = pointEmissionTrouble.active,
                activePointEmissionAccuracyTrouble = pointEmissionTrouble.accuracyBlocked,
                pointEmissionTroubleReason = pointEmissionTrouble.reason,
                lastLocalPointPersistedAtMs = pointFreshnessTracker.lastLocalPointPersistedAtMs,
                lastUploadSucceededAtMs = pointFreshnessTracker.lastUploadSucceededAtMs,
            )
            extraTransform?.invoke(withAccuracy) ?: withAccuracy
        }
    }

    internal fun TrackingServiceHost.buildAccuracyHoldLogKey(
        incomingAccuracyMeters: Float?,
        displayedAccuracyMeters: Float?,
        held: Boolean,
        pointEmissionTrouble: PointEmissionTrouble,
    ): String {
        return "raw=${accuracyMetersBucket(incomingAccuracyMeters)}|" +
            "displayed=${accuracyMetersBucket(displayedAccuracyMeters)}|" +
            "held=$held|force=${pointEmissionTrouble.active}|reason=${pointEmissionTrouble.reason ?: "none"}|" +
            "accBlocked=${pointEmissionTrouble.accuracyBlocked}"
    }

    internal fun TrackingServiceHost.accuracyMetersBucket(meters: Float?): Int {
        if (meters == null || !meters.isFinite() || meters < 0f) return -1
        return meters.toInt()
    }

    internal fun TrackingServiceHost.logPointEmissionTroubleTransition(
        previous: PointEmissionTrouble,
        current: PointEmissionTrouble,
        nowMs: Long,
    ) {
        if (previous.active == current.active && previous.reason == current.reason) return
        val (eventName, details) = PositioningDiagnosticEvent.pointEmissionTrouble(
            active = current.active,
            reason = current.reason ?: previous.reason ?: "none",
            accuracyBlocked = current.accuracyBlocked,
            localAgeMs = pointFreshnessTracker.localPointAgeMs(nowMs),
            uploadAgeMs = pointFreshnessTracker.uploadAgeMs(nowMs),
            gpsState = gpsRuntimeState,
        )
        runtimeTelemetry.event(
            eventName,
            details
        )
    }

    internal suspend fun TrackingServiceHost.restoreLocalFreshnessFromDatabase(
        trackerId: String,
        sessionStartedAtMs: Long,
    ) {
        recoveryAnchorState = recoveryAnchorStore.load(
            trackerId = trackerId,
            sessionBoundaryId = sessionVisibleBoundaryId,
        )
        recoveryAnchorState?.let { anchor ->
            pointFreshnessTracker.seedLocalPointPersistedAt(anchor.timestampMs)
            runtimeTelemetry.event(
                "recovery_anchor_restored",
                "trackerId=$trackerId source=${anchor.source} persistedAtMs=${anchor.timestampMs} " +
                    "localAgeMs=${sessionStartedAtMs - anchor.timestampMs}"
            )
        }
        stationaryFreshnessCoordinator.restore(
            trackerId = trackerId,
            sessionBoundaryId = sessionVisibleBoundaryId,
        )?.let { restored ->
            runtimeTelemetry.event(
                "stationary_region_restored",
                "trackerId=$trackerId enteredAtMs=${restored.enteredAtMs} " +
                    "lastFreshnessPointAtMs=${restored.lastFreshnessPointAtMs}"
            )
        }
        val latestPoint = withContext(Dispatchers.IO) {
            database.locationDao()
                .getRecentChronologicalForTracker(trackerId, limit = 1)
                .lastOrNull()
        } ?: return
        if (latestPoint.time <= 0L) return
        pointFreshnessTracker.seedLocalPointPersistedAt(latestPoint.time)
        runtimeTelemetry.event(
            "freshness_restored_from_db",
            "trackerId=$trackerId persistedAtMs=${latestPoint.time} " +
                "localAgeMs=${sessionStartedAtMs - latestPoint.time} " +
                "sessionBoundaryId=$sessionVisibleBoundaryId"
        )
    }

    internal fun TrackingServiceHost.syncRuntimeStateStore(
        lifecycleStateOverride: TrackingLifecycleState? = null,
        failureReasonOverride: String? = null,
    ) {
        val gpsOk = isGpsProviderEnabled()
        val settings = settingsRepository.getSettings()
        val positioningContext = currentPositioningRuntimeContext(settings)
        val effectiveAccuracyThreshold = positioningContext.effectiveAccuracyThresholdMeters
        validateRuntimeInvariant(gpsProviderEnabled = gpsOk)
        val effectiveRunning = isTrackingActiveOrStarting()
        val selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(service)
        val snapshotForStatus = synchronized(runtimeSnapshotLock) { runtimeSnapshot }
        val providerDecision = providerHealthController.evaluate(
            nowMs = System.currentTimeMillis(),
            isTracking = isTracking,
            expectsActiveFixDelivery = expectsActiveFixDelivery(),
            gpsProviderAvailable = gpsOk,
        )
        val statusProjection = TrackingStatusAccuracyProjector.project(
            TrackingStatusAccuracyInput(
                isRunning = effectiveRunning,
                gpsProviderEnabled = gpsOk,
                gpsState = gpsRuntimeState,
                lastAccuracyMeters = snapshotForStatus.lastAccuracyMeters,
                currentFixAccuracyMeters = snapshotForStatus.currentFixAccuracyMeters,
                effectiveAccuracyThresholdMeters = effectiveAccuracyThreshold,
                activeAccuracyBlockedEmission = snapshotForStatus.activePointEmissionTrouble,
            )
        )
        val activeMotionMode = positioningContext.activeMotionMode
        val selectedTrackerName = SelectedTrackerPrefs.selectedTrackerName(service)
        val next = synchronized(runtimeSnapshotLock) {
            val recordingRuntime = RecordingRuntimeReducer.fromInputs(
                previous = runtimeSnapshot.recordingRuntime,
                sessionActive = isTracking,
                startupActive = startupInProgress,
                gpsState = gpsRuntimeState,
                gpsProviderEnabled = gpsOk,
                selectedTrackerId = selectedTrackerId,
            )
            RuntimeSnapshotProjector.project(
                previous = runtimeSnapshot,
                input = RuntimeSnapshotProjectionInput(
                    isRunning = effectiveRunning,
                    recordingRuntime = recordingRuntime,
                    lifecycleState = lifecycleStateOverride ?: controlState.lifecycleState,
                    failureReason = failureReasonOverride ?: controlState.failureReason,
                    selectedTrackerId = selectedTrackerId,
                    selectedTrackerName = selectedTrackerName,
                    gpsProviderEnabled = gpsOk,
                    autoTrackingEnabled = true,
                    activeMotionMode = activeMotionMode,
                    uiStatus = statusProjection.uiStatus,
                    gpsPaused = recordingRuntime.pausedForMotion,
                    effectiveAccuracyThresholdMeters = effectiveAccuracyThreshold,
                    sessionVisibleBoundaryId = sessionVisibleBoundaryId,
                    providerHealthReason = providerDecision.reason.telemetryValue,
                    uploadLastFailureClass = uploadLivenessState.lastFailureClass,
                    uploadConsecutiveFailures = uploadLivenessState.consecutiveFailures,
                    currentSessionQueuedCount = uploadLivenessState.currentSessionQueuedCount,
                    backlogQueuedCount = uploadLivenessState.backlogQueuedCount,
                )
            ).also { runtimeSnapshot = it }
        }
        TrackingRuntimeStateStore.update { next }
        maybeLogPositioningDiagnosticSnapshot(next)
        if (startupForegroundPromoted && startupInProgress) {
            serviceScope.launch(Dispatchers.Main) {
                notificationPresenter.updateForegroundNotification(runtimeSnapshot)
            }
        }
    }

    internal fun TrackingServiceHost.maybeLogPositioningDiagnosticSnapshot(snapshot: TrackingRuntimeSnapshot) {
        val providerDecision = providerHealthController.evaluate(
            nowMs = System.currentTimeMillis(),
            isTracking = isTracking,
            expectsActiveFixDelivery = expectsActiveFixDelivery(),
            gpsProviderAvailable = snapshot.gpsProviderEnabled,
        )
        val diagnosticSnapshot = PositioningDiagnosticSnapshot(
            gpsState = gpsRuntimeState,
            motionMode = snapshot.activeMotionMode,
            providerHealth = providerDecision.reason.telemetryValue,
            localAgeMs = pointFreshnessTracker.localPointAgeMs(System.currentTimeMillis()),
            uploadAgeMs = pointFreshnessTracker.uploadAgeMs(System.currentTimeMillis()),
            recoveryProbe = if (snapshot.activePointEmissionTrouble) {
                snapshot.pointEmissionTroubleReason ?: "active"
            } else {
                "inactive"
            },
            stationaryRegion = if (stationaryFreshnessCoordinator.hasRegion) {
                "active:probe=${stationaryFreshnessCoordinator.probeActive}:poorFixes=${stationaryFreshnessCoordinator.poorAccuracyFixes}"
            } else {
                "inactive"
            },
            queueCount = uploadLivenessState.currentSessionQueuedCount + uploadLivenessState.backlogQueuedCount,
            uploadFailureClass = uploadLivenessState.lastFailureClass,
        )
        val key = "gps=${diagnosticSnapshot.gpsState}|mode=${diagnosticSnapshot.motionMode}|" +
            "provider=${diagnosticSnapshot.providerHealth}|recovery=${diagnosticSnapshot.recoveryProbe}|" +
            "stationary=${diagnosticSnapshot.stationaryRegion}|queue=${diagnosticSnapshot.queueCount}|" +
            "uploadFailure=${diagnosticSnapshot.uploadFailureClass}"
        if (key == lastPositioningDiagnosticSnapshotKey) return
        lastPositioningDiagnosticSnapshotKey = key
        val (eventName, details) = PositioningDiagnosticEvent.snapshot(diagnosticSnapshot)
        runtimeTelemetry.event(eventName, details)
    }

    internal fun TrackingServiceHost.transitionControlState(event: TrackingControlEvent, failureReason: String? = null) {
        controlState = TrackingControlPlane.transition(
            current = controlState,
            event = event,
            failureReason = failureReason
        )
        syncRuntimeStateStore()
    }

    internal fun TrackingServiceHost.validateRuntimeInvariant(gpsProviderEnabled: Boolean) {
        when {
            !isTracking && gpsRuntimeState != GpsRuntimeState.INACTIVE -> {
                runtimeTelemetry.event(
                    "runtime_invariant_violation",
                    "state=$gpsRuntimeState while isTracking=false"
                )
            }
            isTracking && gpsRuntimeState == GpsRuntimeState.INACTIVE -> {
                runtimeTelemetry.event(
                    "runtime_invariant_violation",
                    "state=INACTIVE while isTracking=true"
                )
            }
            gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER && gpsProviderEnabled -> {
                runtimeTelemetry.event(
                    "runtime_invariant_watch",
                    "state=WAITING_FOR_PROVIDER with providerEnabled=true"
                )
            }
        }
    }
