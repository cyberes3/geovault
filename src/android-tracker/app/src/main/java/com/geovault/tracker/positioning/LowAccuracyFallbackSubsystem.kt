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


    internal fun PositioningRuntime.selectLowAccuracyFallbackCandidate(
        rejectedLocation: Location,
        nowMs: Long,
        motionMode: TrackingMotionMode,
    ): Location {
        val anchor = lastFilteredLocation
        val useAnchor = anchor != null &&
            pointFreshnessTracker.shouldForceLocalRecovery(
                nowMs = nowMs,
                intervalSec = resolvePointFreshnessIntervalSec(motionMode),
            )
        runtimeTelemetry.event(
            "fallback_candidate_selected",
            "source=${if (useAnchor) "anchor" else "rejected_fix"} " +
                "localAgeMs=${pointFreshnessTracker.localPointAgeMs(nowMs) ?: -1L} " +
                "rejectedAccuracy=${if (rejectedLocation.hasAccuracy()) rejectedLocation.accuracy else -1f} " +
                "anchorAccuracy=${if (anchor?.hasAccuracy() == true) anchor.accuracy else -1f}"
        )
        if (useAnchor) {
            return Location(anchor).apply {
                time = nowMs
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                provider = "low_accuracy_fallback_anchor:${rejectedLocation.provider ?: "gps"}"
                if (rejectedLocation.hasAccuracy()) accuracy = rejectedLocation.accuracy
            }
        }
        return Location(rejectedLocation)
    }

    internal fun PositioningRuntime.ensureLowAccuracyFallbackTimerRunning() {
        if (lowAccuracyFallbackJob?.isActive == true) return
        val runGeneration = trackingGeneration
        lowAccuracyFallbackJob = serviceScope.launch(Dispatchers.IO) {
            lowAccuracyFallbackTimerArmedAtMs = System.currentTimeMillis()
            while (isTracking && runGeneration == trackingGeneration) {
                val timeoutSec = TrackerSettings.clampLowAccuracyFallbackTimeoutSec(
                    settingsRepository.getSettings().lowAccuracyFallbackTimeoutSec
                )
                delay(timeoutSec * 1000L)
                if (!isTracking || runGeneration != trackingGeneration) break
                if (
                    gpsRuntimeState == GpsRuntimeState.PAUSED_FOR_MOTION ||
                    gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
                ) {
                    logFallbackWait(reason = "gps_paused state=$gpsRuntimeState")
                    continue
                }
                val candidate = lowAccuracyFallbackCandidate
                if (candidate == null) {
                    logFallbackWait(reason = "no_candidate")
                    continue
                }
                val settings = settingsRepository.getSettings()
                val loopDecision = lowAccuracyFallbackCoordinator.evaluateLoop(
                        fallbackEligible = settings.lowAccuracyFallbackEnabled,
                        hasCandidate = true
                )
                if (loopDecision != LowAccuracyFallbackLoopDecision.COMMIT_ANCHOR) {
                    logFallbackWait(reason = loopDecision.telemetryValue)
                    continue
                }
                lastLowAccuracyFallbackWaitReason = null
                val fallbackLocation = Location(candidate).apply {
                    provider = "low_accuracy_fallback:${candidate.provider ?: "gps"}"
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    extras = (extras ?: Bundle()).apply {
                        putBoolean(TrackingServiceConstants.EXTRAS_KEY_LOW_ACCURACY_FALLBACK, true)
                        putString(TrackingServiceConstants.EXTRAS_KEY_FALLBACK_SOURCE_PROVIDER, candidate.provider ?: "gps")
                    }
                }
                if (
                    !shouldEmitFallbackForTransition(
                        previousAcceptedLocation = lastFilteredLocation,
                        fallbackCandidateLocation = fallbackLocation,
                        nowMs = fallbackLocation.time
                    )
                ) {
                    runtimeTelemetry.event("fallback_rejected", "reason=implausible_transition")
                    continue
                }
                lowAccuracyFallbackCoordinator.onFallbackEmitted(
                    candidateLatitude = candidate.latitude,
                    candidateLongitude = candidate.longitude,
                    candidateTimestampMs = candidate.time
                )
                lowAccuracyFallbackEmitCountThisSession++
                transitionGpsState(GpsRuntimeEvent.FALLBACK_EMITTED, "fallback_emitted")
                lowAccuracyFallbackTimerArmedAtMs = System.currentTimeMillis()
                val shouldPersistFallback = shouldPersistFallbackPoint(lastFilteredLocation, fallbackLocation) ||
                    pointFreshnessTracker.shouldForceLocalRecovery(
                        nowMs = fallbackLocation.time,
                        intervalSec = resolvePointFreshnessIntervalSec(resolveActiveMotionMode()),
                    )
                if (!shouldPersistFallback) {
                    runtimeTelemetry.event("fallback_skipped_persist", "reason=accuracy_uncertainty")
                    continue
                }
                processLocationUpdateSerialized(
                    location = fallbackLocation,
                    bypassFilters = true
                )
            }
            lowAccuracyFallbackTimerArmedAtMs = 0L
            lowAccuracyFallbackJob = null
            if (isTracking && runGeneration == trackingGeneration) {
                lowAccuracyFallbackCoordinator.onFallbackTimerStopped()
            }
        }
    }

    internal fun PositioningRuntime.cancelLowAccuracyFallbackTimer(clearCandidate: Boolean) {
        if (lowAccuracyFallbackJob != null) {
            lowAccuracyFallbackCancelCountThisSession++
        }
        lowAccuracyFallbackJob?.cancel()
        lowAccuracyFallbackJob = null
        lowAccuracyFallbackTimerArmedAtMs = 0L
        if (clearCandidate) {
            lowAccuracyFallbackCoordinator.onTrackingStopped()
        } else {
            lowAccuracyFallbackCoordinator.onFallbackTimerStopped()
        }
        if (clearCandidate) {
            lowAccuracyFallbackCandidate = null
        }
    }

    internal fun PositioningRuntime.logFallbackWait(reason: String) {
        if (lastLowAccuracyFallbackWaitReason == reason) return
        lastLowAccuracyFallbackWaitReason = reason
        val (eventName, details) = PositioningDiagnosticEvent.fallbackWait(reason)
        runtimeTelemetry.event(eventName, details)
    }

    internal fun PositioningRuntime.maybeLogFallbackRejectSummary(nowMs: Long) {
        if (nowMs - lowAccuracyFallbackLastRejectSummaryAtMs < TrackingServiceConstants.FALLBACK_REJECT_SUMMARY_INTERVAL_MS) return
        lowAccuracyFallbackLastRejectSummaryAtMs = nowMs
        runtimeTelemetry.event(
            "fallback_reject_summary",
            "rejected=$lowAccuracyFallbackRejectedFixCountThisSession armed=$lowAccuracyFallbackArmCountThisSession emitted=$lowAccuracyFallbackEmitCountThisSession"
        )
    }

    internal fun PositioningRuntime.shouldEmitFallbackForTransition(
        previousAcceptedLocation: Location?,
        fallbackCandidateLocation: Location,
        nowMs: Long,
    ): Boolean = FallbackTransitionPolicy.shouldEmitFallbackForTransition(
        previousAcceptedLocation,
        fallbackCandidateLocation,
        nowMs,
    )

internal fun PositioningRuntime.shouldPersistFallbackPoint(
    previousAcceptedLocation: Location?,
    fallbackLocation: Location,
): Boolean = FallbackPersistencePolicy.shouldPersistFallbackPoint(previousAcceptedLocation, fallbackLocation)
