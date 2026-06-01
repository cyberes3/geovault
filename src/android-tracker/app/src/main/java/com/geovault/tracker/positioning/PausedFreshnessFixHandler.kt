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


    internal fun PositioningRuntime.requestStationaryFreshnessProbe(reason: String): Boolean {
        if (!isTracking) {
            stationaryFreshnessCoordinator.onStopped(reason = "not_tracking")
            runtimeTelemetry.event(
                "stationary_ping_dropped",
                "reason=$reason notTracking=true gpsState=$gpsRuntimeState"
            )
            return false
        }
        if (gpsRuntimeState != GpsRuntimeState.PAUSED_FOR_MOTION &&
            gpsRuntimeState != GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
        ) {
            stationaryFreshnessCoordinator.onResumed(reason = "not_paused")
            clearPausedFreshnessProbe(reason = "stationary_ping_not_paused")
            runtimeTelemetry.event("stationary_ping_skipped", "reason=$reason state=$gpsRuntimeState")
            return true
        }
        runtimeTelemetry.event(
            "stationary_ping_received",
            "reason=$reason state=$gpsRuntimeState lastRaw=${summarizeLocationForTelemetry(latestObservedRawLocation)} " +
                "lastAccepted=${summarizeLocationForTelemetry(lastFilteredLocation)}"
        )
        if (gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED) {
            clearPausedFreshnessProbe(reason = "provider_unavailable_before_probe")
            stationaryFreshnessCoordinator.schedulePausedPing(
                reason = "provider_unavailable_before_probe",
                providerAvailable = false,
            )
            runtimeTelemetry.event("stationary_ping_deferred", "reason=$reason state=$gpsRuntimeState")
            return true
        }
        markPausedFreshnessProbeStarted(nowMs = System.currentTimeMillis())
        resumeGps(reason = "stationary_ping_resume")
        return true
    }

    internal suspend fun PositioningRuntime.handlePausedFreshnessProbeFix(
        selectedTrackerId: String,
        probeLocation: Location,
        anchorLocation: Location?,
        settings: TrackerSettings,
        motionMode: TrackingMotionMode,
        nowMs: Long,
        nowElapsedRealtimeNanos: Long,
    ): Boolean {
        val runtimeContext = currentPositioningRuntimeContext(settings)
        val decision = PausedFreshnessPolicy.evaluate(
            anchorLocation = anchorLocation,
            candidateLocation = probeLocation,
            stationaryRadiusMeters = stationaryFreshnessCoordinator.radiusMeters
                .takeIf { stationaryFreshnessCoordinator.hasRegion }
                ?: TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS,
            accuracyCeilingMeters = runtimeContext.stationaryAccuracyCeilingMeters,
            freshnessIntervalMs = runtimeContext.stationaryProbeIntervalMs,
            nowMs = nowMs,
            lastFreshnessPointAtMs = stationaryFreshnessCoordinator.lastFreshnessPointAtMs,
        )
        if (!decision.shouldEmit) {
            logPausedFreshnessDecision(eventName = "paused_freshness_skipped", decision = decision, probeLocation = probeLocation)
            when (decision.reason) {
                PausedFreshnessDecisionReason.MOVED -> {
                    clearPausedFreshnessProbe(reason = decision.reason.telemetryValue)
                    return false
                }
                PausedFreshnessDecisionReason.NO_ANCHOR -> {
                    clearPausedFreshnessProbe(reason = decision.reason.telemetryValue)
                    pauseGpsInternal(force = true)
                    return true
                }
                PausedFreshnessDecisionReason.POOR_ACCURACY -> {
                    val probeState = stationaryFreshnessCoordinator.recordPoorAccuracyFix(nowMs)
                    if (
                        probeState.poorAccuracyFixes >= TrackingServiceConstants.PAUSED_FRESHNESS_MAX_POOR_ACCURACY_FIXES ||
                        probeState.probeAgeMs >= TrackingServiceConstants.PAUSED_FRESHNESS_PROBE_TIMEOUT_MS
                    ) {
                        clearPausedFreshnessProbe(reason = "poor_accuracy_timeout")
                        if (pointFreshnessTracker.shouldForceLocalRecovery(
                                nowMs = nowMs,
                                intervalSec = resolvePointFreshnessIntervalSec(motionMode),
                            )
                        ) {
                            runtimeTelemetry.event(
                                "paused_freshness_kept_awake",
                                "reason=poor_accuracy localAgeMs=${pointFreshnessTracker.localPointAgeMs(nowMs) ?: -1L}"
                            )
                        } else {
                            pauseGpsInternal(force = true)
                        }
                    }
                    return true
                }
                PausedFreshnessDecisionReason.TOO_SOON -> {
                    clearPausedFreshnessProbe(reason = "too_soon")
                    pauseGpsInternal(force = true)
                    return true
                }
                PausedFreshnessDecisionReason.EMIT -> return false
            }
        }

        val anchor = anchorLocation ?: run {
            clearPausedFreshnessProbe(reason = "emit_without_anchor")
            pauseGpsInternal(force = true)
            return true
        }
        val freshnessLocation = PausedFreshnessPointFactory.buildAnchoredFreshnessLocation(
            anchorLocation = anchor,
            probeLocation = probeLocation,
            nowMs = nowMs,
            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
        )
        val persisted = persistPausedFreshnessPoint(
            selectedTrackerId = selectedTrackerId,
            freshnessLocation = freshnessLocation,
            previousAcceptedLocation = anchor,
            settings = settings,
            motionMode = motionMode,
            nowMs = nowMs,
            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
        )
        if (!persisted) {
            clearPausedFreshnessProbe(reason = "persist_rejected")
            pauseGpsInternal(force = true)
            return true
        }
        stationaryFreshnessCoordinator.markFreshnessPointPersisted(nowMs)
        logPausedFreshnessDecision(eventName = "paused_freshness_emitted", decision = decision, probeLocation = probeLocation)
        runtimeTelemetry.event(
            name = "track_point",
            details = "lat=%.8f lon=%.8f accuracy=%.1f speed=%.2f reason=paused-freshness source=paused_freshness".format(
                freshnessLocation.latitude,
                freshnessLocation.longitude,
                if (freshnessLocation.hasAccuracy()) freshnessLocation.accuracy else -1f,
                if (freshnessLocation.hasSpeed()) freshnessLocation.speed else -1f,
            )
        )
        clearPausedFreshnessProbe(reason = "emitted")
        pauseGpsInternal(force = true)
        runtimeTelemetry.event(
            "paused_freshness_repaused",
            "intervalMs=${currentPositioningRuntimeContext(settings).stationaryProbeIntervalMs}"
        )
        return true
    }

    internal fun PositioningRuntime.markPausedFreshnessProbeStarted(nowMs: Long) {
        val anchorAgeMs = lastFilteredLocation?.time?.let { nowMs - it }
        stationaryFreshnessCoordinator.startProbe(
            nowMs = nowMs,
            timeoutMs = TrackingServiceConstants.PAUSED_FRESHNESS_PROBE_TIMEOUT_MS,
            details = "state=$gpsRuntimeState consecutiveStationary=$consecutiveStationaryPoints " +
                "anchorAgeMs=${anchorAgeMs ?: -1L}",
        )
    }

    internal fun PositioningRuntime.clearPausedFreshnessProbe(
        reason: String,
        clearLastFreshnessTimestamp: Boolean = false,
    ) {
        stationaryFreshnessCoordinator.clearProbe(
            reason = reason,
            clearLastFreshnessTimestamp = clearLastFreshnessTimestamp,
        )
    }

    internal fun PositioningRuntime.logPausedFreshnessDecision(
        eventName: String,
        decision: PausedFreshnessDecision,
        probeLocation: Location,
    ) {
        runtimeTelemetry.event(
            eventName,
            "reason=${decision.reason.telemetryValue} " +
                "distance=${decision.distanceMeters ?: -1f} " +
                "accuracy=${decision.accuracyMeters ?: -1f} " +
                "elapsedSinceLast=${decision.elapsedSinceLastFreshnessMs ?: -1L} " +
                "provider=${probeLocation.provider ?: "unknown"}"
        )
    }

    internal suspend fun PositioningRuntime.persistPausedFreshnessPoint(
        selectedTrackerId: String,
        freshnessLocation: Location,
        previousAcceptedLocation: Location,
        settings: TrackerSettings,
        motionMode: TrackingMotionMode,
        nowMs: Long,
        nowElapsedRealtimeNanos: Long,
    ): Boolean {
        val runtimeContext = currentPositioningRuntimeContext(settings)
        val pipelineOutput = trackerLocationPipeline.processFix(
            input = TrackerLocationPipelineInput(
                trackId = selectedTrackerId,
                location = freshnessLocation,
                settings = settings,
                motionContext = TrackerLocationMotionContext(
                    motionMode = motionMode,
                    filterConfig = runtimeContext.filterConfig,
                    effectiveAccuracyThresholdMeters = runtimeContext.effectiveAccuracyThresholdMeters,
                ),
                previousAcceptedLocation = previousAcceptedLocation,
                sessionVisibleBoundaryId = sessionVisibleBoundaryId,
                ingestMode = FixIngestMode.PausedFreshness,
                propsJson = null,
                totalDistanceMeters = runtimeSnapshot.sessionTotalDistanceMeters,
                nowMs = nowMs,
                nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
                sessionStartTimeMs = runtimeSnapshot.sessionStartTimeMs,
                isMockLocation = LocationCompat.isMock(freshnessLocation),
                skipAdaptiveTrackingEffects = true,
                localRecoveryDue = false,
                recoveryConfig = runtimeContext.recoveryConfig,
                recoveryAnchor = recoveryAnchorState,
                outlierSuppressorAnchor = lastFilteredLocation,
            ),
            onAutoMotionRejected = { result, _, _ ->
                AutoMotionRejectHandling.Rejected(
                    output = AutoTrackingEngineOutput(
                        state = AutoTrackingMotionState(mode = motionMode),
                        modeChanged = false,
                    ),
                    rejectReason = result.rejectReason,
                    policyReason = result.policyMetrics?.reason,
                )
            },
            refreshMotionContext = {
                TrackerLocationMotionContext(
                    motionMode = motionMode,
                    filterConfig = runtimeContext.filterConfig,
                    effectiveAccuracyThresholdMeters = runtimeContext.effectiveAccuracyThresholdMeters,
                )
            },
            buildFreshnessRecoveryLocation = { anchor, _, recoveryNowMs, recoveryNanos ->
                anchor.toLocation(providerPrefix = "paused_freshness").apply {
                    time = recoveryNowMs
                    elapsedRealtimeNanos = recoveryNanos
                }
            },
        )
        val result = pipelineOutput.result
        if (!result.accepted || !result.pointPersisted) {
            runtimeTelemetry.event(
                "paused_freshness_persist_skipped",
                "accepted=${result.accepted} persisted=${result.pointPersisted} " +
                    "reason=${result.rejectReason ?: result.adjustmentReason ?: "none"}"
            )
            return false
        }

        val acceptedLocation = result.lastFilteredLocation ?: freshnessLocation
        pointFreshnessTracker.markLocalPointPersisted(nowMs)
        freshnessRecoveryController.reset()
        updateRecoveryAnchor(
            location = acceptedLocation,
            source = "paused_freshness",
            motionMode = motionMode,
        )
        lastFilteredLocation = acceptedLocation
        lastSpeedReferenceLocation = Location(acceptedLocation)
        val finalPropsJson = buildLocalPointPropsJson(
            location = acceptedLocation,
            distanceMeters = result.nextSessionDistanceMeters,
        )
        applyAccuracyHoldUpdate(
            incomingAccuracyMeters = result.lastAccuracyMeters,
            extraTransform = { snapshot ->
                snapshot.copy(
                    queuedPointsVisible = result.queuedPointsVisible,
                    sessionTotalDistanceMeters = result.nextSessionDistanceMeters,
                    lastTrackedLatitude = result.lastTrackedLatitude,
                    lastTrackedLongitude = result.lastTrackedLongitude,
                    lastTrackedTimestampMs = result.lastTrackedTimestampMs,
                    lastTrackedPropsJson = finalPropsJson,
                )
            },
        )
        publishTrackPoint(
            trackId = selectedTrackerId,
            location = acceptedLocation,
            propsJson = finalPropsJson,
            quality = resolveTrackPointQuality(acceptedLocation, finalPropsJson),
        )
        withContext(Dispatchers.Main) {
            syncRuntimeStateStore()
            updateNotificationFromDb(broadcastStats = false)
        }
        serviceScope.launch(Dispatchers.IO) {
            val outcome = pushQueuedLocations(
                scope = QueueUploadScope.LIVE_ONLY,
                updateFailureCounters = false
            )
            if (outcome == SyncFailureClass.NONE) {
                consecutivePushFailures = 0
            }
        }
        return true
    }
