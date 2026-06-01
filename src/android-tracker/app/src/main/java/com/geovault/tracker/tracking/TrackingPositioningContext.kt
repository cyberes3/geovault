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


    internal fun TrackingServiceHost.effectivePositioningPreset(
        motionMode: TrackingMotionMode,
        settings: TrackerSettings = settingsRepository.getSettings(),
    ): PositioningPresetValues {
        return PositioningPresets.forMotionMode(
            motionMode,
            PositioningDensity.from(settings),
        )
    }

    internal fun TrackingServiceHost.resolvePointFreshnessIntervalSec(motionMode: TrackingMotionMode): Long {
        return effectivePositioningPreset(motionMode).locationIntervalSec
    }

    internal fun TrackingServiceHost.resolveActiveMotionMode(): TrackingMotionMode {
        return autoTrackingMotionEngine.snapshot().mode
    }

    internal fun TrackingServiceHost.startSparseTrackingObserver() {
        sparseTrackingObserverJob?.cancel()
        sparseTrackingObserverJob = serviceScope.launch {
            settingsRepository.observeSettings()
                .map { it.sparseTracking }
                .distinctUntilChanged()
                .drop(1)
                .collect { onSparseTrackingChanged() }
        }
    }

    internal fun TrackingServiceHost.onSparseTrackingChanged() {
        resetElasticDistanceOverride(reason = "sparse_tracking_changed", reapplyRequest = false)
        reapplyLocationRequestIfActive("sparse_tracking_changed")
        val probeIntervalMs = currentPositioningRuntimeContext().stationaryProbeIntervalMs
        if (
            gpsRuntimeState == GpsRuntimeState.PAUSED_FOR_MOTION ||
            gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
        ) {
            stationaryPingController.reschedulePausedPing(
                newIntervalMs = probeIntervalMs,
                providerAvailable = isGpsProviderEnabled(),
                reason = "sparse_tracking_changed",
            )
        }
        runtimeTelemetry.event(
            "sparse_tracking_changed",
            "probeIntervalMs=$probeIntervalMs sparse=${settingsRepository.getSettings().sparseTracking}"
        )
    }

    internal fun TrackingServiceHost.currentPositioningRecoveryConfig(): PositioningRecoveryConfig {
        return currentPositioningRuntimeContext(settingsRepository.getSettings()).recoveryConfig
    }

    internal fun TrackingServiceHost.currentPositioningRuntimeContext(
        settings: TrackerSettings = settingsRepository.getSettings(),
    ): TrackerPositioningRuntimeContext {
        val motionMode = resolveActiveMotionMode()
        val preset = effectivePositioningPreset(motionMode, settings)
        val baseDistance = preset.distanceFilterMeters
        return TrackerPositioningRuntimeContext.build(
            settings = settings,
            activeMotionMode = motionMode,
            effectiveDistanceFilterMeters = elasticDistanceOverrideMeters ?: baseDistance,
            localPointMaxGapMs = pointFreshnessTracker.maxAllowedPointGapMs(preset.locationIntervalSec),
        )
    }

    internal fun TrackingServiceHost.resolvePointEmissionTrouble(
        result: com.geovault.tracker.services.LocationIngestResult,
        nowMs: Long,
        motionMode: TrackingMotionMode,
        effectiveAccuracyThresholdMeters: Float,
    ): PointEmissionTrouble {
        if (result.accepted && result.pointPersisted) return PointEmissionTrouble.None
        val staleLocal = pointFreshnessTracker.shouldForceLocalRecovery(
            nowMs = nowMs,
            intervalSec = resolvePointFreshnessIntervalSec(motionMode),
        )
        if (!staleLocal) return PointEmissionTrouble.None
        val policyReason = result.policyMetrics?.reason
        val accuracyBlocked = result.rejectReason == TrackPointRejectReason.BAD_ACCURACY ||
            result.rejectReason == TrackPointRejectReason.STALE ||
            result.lastAccuracyMeters == null ||
            result.lastAccuracyMeters > effectiveAccuracyThresholdMeters ||
            gpsRuntimeState == GpsRuntimeState.FALLBACK_PENDING
        val reason = when {
            accuracyBlocked -> result.rejectReason?.name ?: "accuracy"
            policyReason != null -> policyReason
            result.adjustmentReason != null -> result.adjustmentReason
            else -> "stale_local_point"
        }
        return PointEmissionTrouble(
            active = true,
            accuracyBlocked = accuracyBlocked,
            reason = reason,
        )
    }

    internal fun TrackingServiceHost.maybeLogFreshnessProbeDecision(
        decision: FreshnessRecoveryDecision,
        result: com.geovault.tracker.services.LocationIngestResult,
        nowMs: Long,
        motionMode: TrackingMotionMode,
    ) {
        if (decision == FreshnessRecoveryDecision.Inactive) return
        if (!freshnessRecoveryController.shouldLog(decision)) return
        val eventName = when (decision) {
            is FreshnessRecoveryDecision.ProbeStarted -> "freshness_probe_started"
            is FreshnessRecoveryDecision.ProbeWait -> "freshness_probe_wait"
            is FreshnessRecoveryDecision.Blocked -> "freshness_probe_blocked"
            FreshnessRecoveryDecision.CommitAnchor -> "freshness_probe_commit"
            FreshnessRecoveryDecision.Inactive -> "freshness_probe_inactive"
        }
        runtimeTelemetry.event(
            eventName,
            "reason=${decision.telemetryValue} " +
                "filterReason=${result.policyMetrics?.reason ?: result.rejectReason ?: "none"} " +
                "localAgeMs=${pointFreshnessTracker.localPointAgeMs(nowMs) ?: -1L} " +
                "uploadAgeMs=${pointFreshnessTracker.uploadAgeMs(nowMs) ?: -1L} " +
                "maxGapMs=${pointFreshnessTracker.maxAllowedPointGapMs(resolvePointFreshnessIntervalSec(motionMode))}"
        )
    }

    internal fun TrackingServiceHost.buildFreshnessRecoveryLocation(
        anchor: RecoveryAnchorState,
        sourceLocation: Location,
        nowMs: Long,
        nowElapsedRealtimeNanos: Long,
    ): Location {
        val sourceProvider = sourceLocation.provider?.takeIf { it.isNotBlank() } ?: "gps"
        return anchor.toLocation(providerPrefix = "freshness_recovery").apply {
            time = nowMs
            elapsedRealtimeNanos = nowElapsedRealtimeNanos
            provider = "freshness_recovery:$sourceProvider"
            if (sourceLocation.hasAccuracy()) accuracy = sourceLocation.accuracy
            extras = (extras ?: Bundle()).apply {
                putBoolean(TrackingServiceConstants.EXTRAS_KEY_FRESHNESS_RECOVERY, true)
                putString(TrackingServiceConstants.EXTRAS_KEY_FRESHNESS_RECOVERY_SOURCE_PROVIDER, sourceProvider)
            }
        }
    }

    internal fun TrackingServiceHost.updateRecoveryAnchor(
        location: Location,
        source: String,
        motionMode: TrackingMotionMode,
    ) {
        val trackerId = SelectedTrackerPrefs.selectedTrackerId(service)
        if (trackerId.isBlank()) return
        val anchor = RecoveryAnchorState.fromLocation(
            trackerId = trackerId,
            sessionBoundaryId = sessionVisibleBoundaryId,
            location = location,
            radiusMeters = TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS,
            source = source,
            motionMode = motionMode,
        )
        recoveryAnchorState = anchor
        recoveryAnchorStore.save(anchor)
    }
