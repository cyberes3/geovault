package com.geovault.tracker.positioning
import com.geovault.tracker.tracking.TrackingServiceConstants



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


    internal fun PositioningRuntime.startAutoModeTickIfNeeded() {
        if (!isTracking) return
        if (autoModeTickJob?.isActive == true) return
        autoModeTickJob = serviceScope.launch {
            while (isTracking) {
                delay(5_000L)
                processAutoTrackingOutput(
                    output = autoTrackingMotionEngine.onTick(System.currentTimeMillis()),
                    reason = "periodic_decay_tick"
                )
            }
        }
    }

    internal fun PositioningRuntime.stopAutoModeTick() {
        autoModeTickJob?.cancel()
        autoModeTickJob = null
    }

    internal fun PositioningRuntime.handleAutoMotionRejectedFix(
        result: LocationIngestResult,
        location: Location,
        nowMs: Long,
    ): AutoMotionRejectHandling {
        val handling = autoTrackingMotionCoordinator.onRejectedOrHeld(
            metrics = result.policyMetrics,
            rejectReason = result.rejectReason,
            eventTimeMs = location.time,
            nowMs = nowMs,
        )
        when (handling) {
            is AutoMotionRejectHandling.Evidence -> {
                runtimeTelemetry.event(
                    name = "auto_motion_evidence",
                    details = "modeBefore=${handling.modeBefore} modeAfter=${handling.output.state.mode} " +
                        "reason=${handling.policyReason ?: "unknown"} speed=${handling.evidence.speedMps} " +
                        "accuracy=${handling.accuracyMeters ?: -1f} dt=${handling.elapsedSeconds} " +
                        "path=${handling.evidence.path}"
                )
                processAutoTrackingOutput(
                    output = handling.output,
                    reason = "motion_evidence_${handling.policyReason ?: "unknown"}",
                )
            }
            is AutoMotionRejectHandling.Preserved -> {
                runtimeTelemetry.event(
                    name = "auto_motion_streak_preserved",
                    details = "rejectReason=${handling.rejectReason} policyReason=${handling.policyReason ?: "none"} " +
                        "elapsedSinceCapEvidenceMs=${handling.elapsedSinceCapEvidenceMs}"
                )
            }
            is AutoMotionRejectHandling.Rejected -> {
                processAutoTrackingOutput(
                    output = handling.output,
                    reason = "rejected_fix"
                )
            }
        }
        return handling
    }

    internal fun PositioningRuntime.processAutoTrackingOutput(output: AutoTrackingEngineOutput, reason: String) {
        if (output.modeChanged) {
            lastAutoModeChangedAtMs = System.currentTimeMillis()
            resetElasticDistanceOverride(reason = "auto_mode_changed_$reason", reapplyRequest = false)
            reapplyLocationRequestIfActive("auto_mode_$reason")
            runtimeTelemetry.event(
                name = "auto_mode_changed",
                details = "mode=${output.state.mode} reason=$reason path=${output.transitionPath}"
            )
        }
        syncRuntimeStateStore()
    }

    internal fun PositioningRuntime.maybeApplyElasticDistanceFilter(observedSpeedMps: Float?, measuredAccuracyMeters: Float?) {
        if (!isTracking) return
        if (
            gpsRuntimeState == GpsRuntimeState.PAUSED_FOR_MOTION ||
            gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER ||
            gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
        ) {
            return
        }
        if (isFastGpsLockWindowActive) return
        val mode = autoTrackingMotionEngine.snapshot().mode
        val baseDistanceMeters = effectivePositioningPreset(mode).distanceFilterMeters
        if (baseDistanceMeters <= 0f) return
        val runtimeContext = currentPositioningRuntimeContext()
        val accuracyThresholdMeters = runtimeContext.effectiveAccuracyThresholdMeters
        if (measuredAccuracyMeters == null || measuredAccuracyMeters > accuracyThresholdMeters) return
        val nextBucket = computeElasticitySpeedBucket(observedSpeedMps)
        val nextDistance = computeElasticDistanceFilterMeters(baseDistanceMeters, nextBucket)
        if (!nextDistance.isFinite() || nextDistance < baseDistanceMeters) return
        val currentDistance = elasticDistanceOverrideMeters ?: baseDistanceMeters
        val distanceDelta = kotlin.math.abs(nextDistance - currentDistance)
        if (nextBucket == elasticitySpeedBucket && distanceDelta < TrackingServiceConstants.ELASTICITY_REAPPLY_DISTANCE_DELTA_METERS) return
        elasticitySpeedBucket = nextBucket
        elasticDistanceOverrideMeters = if (nextBucket > 0) nextDistance else null
        reapplyLocationRequestIfActive("elasticity_update")
        runtimeTelemetry.decision(
            name = "elasticity_updated",
            details = "base=$baseDistanceMeters speed=${observedSpeedMps ?: -1f} bucket=$nextBucket distance=$nextDistance"
        )
    }

    internal fun PositioningRuntime.resetElasticDistanceOverride(reason: String, reapplyRequest: Boolean) {
        val changed = elasticDistanceOverrideMeters != null || elasticitySpeedBucket != 0
        elasticDistanceOverrideMeters = null
        elasticitySpeedBucket = 0
        if (changed) {
            runtimeTelemetry.event("elasticity_reset", "reason=$reason")
        }
        if (reapplyRequest) {
            reapplyLocationRequestIfActive("elasticity_reset_$reason")
        }
    }

    internal fun PositioningRuntime.computeElasticitySpeedBucket(speedMps: Float?): Int {
        if (speedMps == null || !speedMps.isFinite() || speedMps <= 0f) return 0
        val bucket = kotlin.math.round(speedMps / TrackingServiceConstants.ELASTICITY_SPEED_BUCKET_SIZE_MPS).toInt()
        return bucket.coerceIn(0, TrackingServiceConstants.ELASTICITY_MAX_SPEED_BUCKET)
    }

    internal fun PositioningRuntime.computeElasticDistanceFilterMeters(baseDistanceMeters: Float, speedBucket: Int): Float {
        val base = baseDistanceMeters.coerceAtLeast(0f)
        if (speedBucket <= 0 || base <= 0f) return base
        val extra = base * TrackingServiceConstants.ELASTICITY_MULTIPLIER * speedBucket.toFloat()
        return (base + extra).coerceAtMost(TrackingServiceConstants.ELASTICITY_MAX_DISTANCE_FILTER_METERS)
    }
