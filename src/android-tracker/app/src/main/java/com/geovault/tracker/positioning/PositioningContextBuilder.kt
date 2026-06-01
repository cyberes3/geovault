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

internal class PositioningContextBuilder(private val rt: PositioningRuntime) {
    fun effectivePositioningPreset(
        motionMode: TrackingMotionMode,
        settings: TrackerSettings = rt.deps.settingsRepository.getSettings(),
    ): PositioningPresetValues {
        return PositioningPresets.forMotionMode(
            motionMode,
            PositioningDensity.from(settings),
        )
    }

    fun resolvePointFreshnessIntervalSec(motionMode: TrackingMotionMode): Long {
        return rt.contextBuilder.effectivePositioningPreset(motionMode).locationIntervalSec
    }

    fun resolveActiveMotionMode(): TrackingMotionMode {
        return rt.deps.autoTrackingMotionEngine.snapshot().mode
    }

    fun startSparseTrackingObserver() {
        rt.state.sparseTrackingObserverJob?.cancel()
        rt.state.sparseTrackingObserverJob = rt.serviceScope.launch {
            rt.deps.settingsRepository.observeSettings()
                .map { it.sparseTracking }
                .distinctUntilChanged()
                .drop(1)
                .collect { rt.contextBuilder.onSparseTrackingChanged() }
        }
    }

    fun onSparseTrackingChanged() {
        rt.motion.resetElasticDistanceOverride(reason = "sparse_tracking_changed", reapplyRequest = false)
        rt.locationRequests.reapplyLocationRequestIfActive("sparse_tracking_changed")
        val probeIntervalMs = rt.contextBuilder.currentPositioningRuntimeContext().stationaryProbeIntervalMs
        if (
            rt.state.gpsRuntimeState == GpsRuntimeState.PAUSED_FOR_MOTION ||
            rt.state.gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
        ) {
            rt.deps.stationaryPingController.reschedulePausedPing(
                newIntervalMs = probeIntervalMs,
                providerAvailable = rt.utilities.isGpsProviderEnabled(),
                reason = "sparse_tracking_changed",
            )
        }
        rt.deps.runtimeTelemetry.event(
            "sparse_tracking_changed",
            "probeIntervalMs=$probeIntervalMs sparse=${rt.deps.settingsRepository.getSettings().sparseTracking}"
        )
    }

    fun currentPositioningRecoveryConfig(): PositioningRecoveryConfig {
        return rt.contextBuilder.currentPositioningRuntimeContext(rt.deps.settingsRepository.getSettings()).recoveryConfig
    }

    fun currentPositioningRuntimeContext(
        settings: TrackerSettings = rt.deps.settingsRepository.getSettings(),
    ): PositioningContext {
        val motionMode = rt.contextBuilder.resolveActiveMotionMode()
        val preset = rt.contextBuilder.effectivePositioningPreset(motionMode, settings)
        val baseDistance = preset.distanceFilterMeters
        return PositioningConfig.resolveContext(
            state = rt.state,
            settings = settings,
            activeMotionMode = motionMode,
            pointFreshnessTracker = rt.deps.pointFreshnessTracker,
        ).let { ctx ->
            val effectiveDistance = rt.state.elasticDistanceOverrideMeters ?: baseDistance
            if (effectiveDistance == ctx.distanceFilterMeters) ctx
            else PositioningContext.build(
                settings = settings,
                activeMotionMode = motionMode,
                effectiveDistanceFilterMeters = effectiveDistance,
                localPointMaxGapMs = rt.deps.pointFreshnessTracker.maxAllowedPointGapMs(preset.locationIntervalSec),
                collectionPace = rt.state.collectionPace,
            )
        }
    }

    fun resolvePointEmissionTrouble(
        result: com.geovault.tracker.services.LocationIngestResult,
        nowMs: Long,
        motionMode: TrackingMotionMode,
        effectiveAccuracyThresholdMeters: Float,
    ): PointEmissionTrouble {
        if (result.accepted && result.pointPersisted) return PointEmissionTrouble.None
        val staleLocal = rt.deps.pointFreshnessTracker.shouldForceLocalRecovery(
            nowMs = nowMs,
            intervalSec = rt.contextBuilder.resolvePointFreshnessIntervalSec(motionMode),
        )
        if (!staleLocal) return PointEmissionTrouble.None
        val policyReason = result.policyMetrics?.reason
        val accuracyBlocked = result.rejectReason == TrackPointRejectReason.BAD_ACCURACY ||
            result.rejectReason == TrackPointRejectReason.STALE ||
            result.lastAccuracyMeters == null ||
            result.lastAccuracyMeters > effectiveAccuracyThresholdMeters ||
            rt.state.gpsRuntimeState == GpsRuntimeState.FALLBACK_PENDING
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

    fun maybeLogFreshnessProbeDecision(
        decision: FreshnessRecoveryDecision,
        result: com.geovault.tracker.services.LocationIngestResult,
        nowMs: Long,
        motionMode: TrackingMotionMode,
    ) {
        if (decision == FreshnessRecoveryDecision.Inactive) return
        if (!rt.deps.freshnessRecoveryController.shouldLog(decision)) return
        val eventName = when (decision) {
            is FreshnessRecoveryDecision.ProbeStarted -> "freshness_probe_started"
            is FreshnessRecoveryDecision.ProbeWait -> "freshness_probe_wait"
            is FreshnessRecoveryDecision.Blocked -> "freshness_probe_blocked"
            FreshnessRecoveryDecision.CommitAnchor -> "freshness_probe_commit"
            FreshnessRecoveryDecision.Inactive -> "freshness_probe_inactive"
        }
        rt.deps.runtimeTelemetry.event(
            eventName,
            "reason=${decision.telemetryValue} " +
                "filterReason=${result.policyMetrics?.reason ?: result.rejectReason ?: "none"} " +
                "localAgeMs=${rt.deps.pointFreshnessTracker.localPointAgeMs(nowMs) ?: -1L} " +
                "uploadAgeMs=${rt.deps.pointFreshnessTracker.uploadAgeMs(nowMs) ?: -1L} " +
                "maxGapMs=${rt.deps.pointFreshnessTracker.maxAllowedPointGapMs(rt.contextBuilder.resolvePointFreshnessIntervalSec(motionMode))}"
        )
    }

    fun buildFreshnessRecoveryLocation(
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

    fun updateRecoveryAnchor(
        location: Location,
        source: String,
        motionMode: TrackingMotionMode,
    ) {
        val trackerId = SelectedTrackerPrefs.selectedTrackerId(rt.ports.service)
        if (trackerId.isBlank()) return
        val anchor = RecoveryAnchorState.fromLocation(
            trackerId = trackerId,
            sessionBoundaryId = rt.state.sessionVisibleBoundaryId,
            location = location,
            radiusMeters = TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS,
            source = source,
            motionMode = motionMode,
        )
        rt.state.recoveryAnchorState = anchor
        rt.deps.recoveryAnchorStore.save(anchor)
    }

}
