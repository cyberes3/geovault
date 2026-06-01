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


    internal suspend fun TrackingServiceHost.processLocationUpdate(
        location: Location,
        bypassFilters: Boolean = false,
        propsJson: String? = null,
        allowWhenGpsPaused: Boolean = false,
        skipAdaptiveTrackingEffects: Boolean = false,
    ) {
        val runGeneration = trackingGeneration
        if (
            !TrackingRuntimeOrchestrator.shouldProcessLocationUpdate(
                RuntimeLocationGateInput(
                    isTracking = isTracking,
                    gpsState = gpsRuntimeState,
                    allowWhenGpsPaused = allowWhenGpsPaused
                )
            )
        ) {
            return
        }
        val settings = settingsRepository.getSettings()
        var runtimeContext = currentPositioningRuntimeContext(settings)
        val previousAcceptedLocation = lastFilteredLocation?.let { Location(it) }
        val nowMs = System.currentTimeMillis()
        val nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        if (isFastGpsLockWindowActive) {
            recordFastGpsLockSample(
                location = location,
                nowMs = nowMs,
                nowElapsedRealtimeNanos = nowElapsedRealtimeNanos
            )
        }
        val observedSpeedMps = resolveObservedSpeedMps(location, lastSpeedReferenceLocation)
        if (!isTracking || runGeneration != trackingGeneration) return
        applyAccuracyHoldUpdate(
            incomingAccuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
        )
        syncRuntimeStateStore()
        val selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(service)
        if (selectedTrackerId.isEmpty()) return
        var motionMode = runtimeContext.activeMotionMode
        if (
            stationaryFreshnessCoordinator.probeActive &&
            !bypassFilters &&
            !skipAdaptiveTrackingEffects &&
            handlePausedFreshnessProbeFix(
                selectedTrackerId = selectedTrackerId,
                probeLocation = location,
                anchorLocation = previousAcceptedLocation
                    ?: recoveryAnchorState?.toLocation(providerPrefix = "paused_recovery_anchor"),
                settings = settings,
                motionMode = motionMode,
                nowMs = nowMs,
                nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
            )
        ) {
            return
        }
        // Independent evidence that the user is actually moving. Gated
        // only on the *current* observed chipset speed; an EMA-smoothed
        // term would carry stale velocity from a prior drive across a
        // long gap and permanently block the stationary counter.
        val activeMotionHint = (observedSpeedMps ?: 0f) > TrackingServiceConstants.MOTION_HINT_FLOOR_MPS
        val pointPropsJson = propsJson
        val pipelineOutput = trackerLocationPipeline.processFix(
            input = TrackerLocationPipelineInput(
                trackId = selectedTrackerId,
                location = location,
                settings = settings,
                motionContext = TrackerLocationMotionContext(
                    motionMode = motionMode,
                    filterConfig = runtimeContext.filterConfig,
                    effectiveAccuracyThresholdMeters = runtimeContext.effectiveAccuracyThresholdMeters,
                ),
                previousAcceptedLocation = previousAcceptedLocation,
                sessionVisibleBoundaryId = sessionVisibleBoundaryId,
                ingestMode = when {
                    bypassFilters -> FixIngestMode.FreshnessBypass
                    else -> FixIngestMode.Live
                },
                propsJson = pointPropsJson,
                totalDistanceMeters = runtimeSnapshot.sessionTotalDistanceMeters,
                nowMs = nowMs,
                nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
                sessionStartTimeMs = runtimeSnapshot.sessionStartTimeMs,
                isMockLocation = LocationCompat.isMock(location),
                skipAdaptiveTrackingEffects = skipAdaptiveTrackingEffects,
                localRecoveryDue = pointFreshnessTracker.shouldForceLocalRecovery(
                    nowMs = nowMs,
                    intervalSec = runtimeContext.pointFreshnessIntervalSec,
                ),
                recoveryConfig = runtimeContext.recoveryConfig,
                recoveryAnchor = recoveryAnchorState,
                outlierSuppressorAnchor = lastFilteredLocation,
            ),
            onAutoMotionRejected = { ingestResult, rejectedLocation, eventNowMs ->
                handleAutoMotionRejectedFix(
                    result = ingestResult,
                    location = rejectedLocation,
                    nowMs = eventNowMs,
                )
            },
            refreshMotionContext = {
                runtimeContext = currentPositioningRuntimeContext(settings)
                TrackerLocationMotionContext(
                    motionMode = runtimeContext.activeMotionMode,
                    filterConfig = runtimeContext.filterConfig,
                    effectiveAccuracyThresholdMeters = runtimeContext.effectiveAccuracyThresholdMeters,
                )
            },
            buildFreshnessRecoveryLocation = { anchor, sourceLocation, recoveryNowMs, recoveryElapsedNanos ->
                buildFreshnessRecoveryLocation(
                    anchor = anchor,
                    sourceLocation = sourceLocation,
                    nowMs = recoveryNowMs,
                    nowElapsedRealtimeNanos = recoveryElapsedNanos,
                )
            },
        )
        var result = pipelineOutput.result
        runtimeContext = currentPositioningRuntimeContext(settings)
        motionMode = pipelineOutput.motionContext.motionMode
        val repeatedOutlierSuppressed = pipelineOutput.repeatedOutlierSuppressed
        if (pipelineOutput.motionModeChanged) {
            runtimeTelemetry.event(
                name = "auto_motion_retry",
                details = "mode=$motionMode accepted=${result.accepted} " +
                    "reason=${result.policyMetrics?.reason ?: result.rejectReason ?: "none"}"
            )
        }
        val freshnessRecoveryDecision = pipelineOutput.freshnessRecoveryDecision
        if (freshnessRecoveryDecision == FreshnessRecoveryDecision.CommitAnchor) {
            runtimeTelemetry.event(
                "freshness_probe_commit",
                "reason=${result.policyMetrics?.reason ?: result.rejectReason ?: "none"} " +
                    "accuracy=${if (location.hasAccuracy()) location.accuracy else -1f} " +
                    "localAgeMs=${pointFreshnessTracker.localPointAgeMs(nowMs) ?: -1L} " +
                    "uploadAgeMs=${pointFreshnessTracker.uploadAgeMs(nowMs) ?: -1L}"
            )
        } else {
            maybeLogFreshnessProbeDecision(
                decision = freshnessRecoveryDecision,
                result = result,
                nowMs = nowMs,
                motionMode = motionMode,
            )
        }
        val nextSessionDistance = result.nextSessionDistanceMeters
        val pointEmissionTrouble = resolvePointEmissionTrouble(
            result = result,
            nowMs = nowMs,
            motionMode = motionMode,
            effectiveAccuracyThresholdMeters = runtimeContext.effectiveAccuracyThresholdMeters,
        )
        applyAccuracyHoldUpdate(
            incomingAccuracyMeters = result.lastAccuracyMeters,
            pointEmissionTrouble = pointEmissionTrouble,
            extraTransform = { snapshot ->
                snapshot.copy(
                    sessionTotalDistanceMeters = if (result.accepted) nextSessionDistance else snapshot.sessionTotalDistanceMeters
                )
            },
        )
        result.policyMetrics?.let { metrics ->
            val filterReason = metrics.reason ?: result.rejectReason ?: result.adjustmentReason ?: "none"
            val signature = "decision=${metrics.decision}|reason=$filterReason|accepted=${result.accepted}"
            if (signature != lastLocationFilterLogSignature) {
                lastLocationFilterLogSignature = signature
                val rawLat = metrics.rawLatitude ?: location.latitude
                val rawLon = metrics.rawLongitude ?: location.longitude
                val committedLat = metrics.committedLatitude?.toString() ?: "none"
                val committedLon = metrics.committedLongitude?.toString() ?: "none"
                runtimeTelemetry.decision(
                    name = "location_filter",
                    details = "raw=${metrics.rawDistanceMeters} effective=${metrics.effectiveDistanceMeters} " +
                        "dt=${metrics.elapsedSeconds} impliedSpeed=${metrics.impliedSpeedMps} " +
                        "accuracy=${metrics.accuracyMeters ?: -1f} rollingAverage=${metrics.rollingAverageStepMeters} " +
                        "capCandidate=${metrics.capCandidateMeters} decision=${metrics.decision} " +
                        "reason=$filterReason " +
                        "rawLat=$rawLat rawLon=$rawLon " +
                        "committedLat=$committedLat committedLon=$committedLon"
                )
            }
        }
        if (!result.accepted && result.policyMetrics == null) {
            val filterReason = result.rejectReason ?: result.adjustmentReason ?: "none"
            val signature = "decision=none|reason=$filterReason|accepted=false"
            if (signature != lastLocationFilterLogSignature) {
                lastLocationFilterLogSignature = signature
                runtimeTelemetry.decision(
                    name = "location_filter",
                    details = "accepted=false reason=$filterReason " +
                        "accuracy=${result.lastAccuracyMeters ?: -1f} " +
                        "lat=${location.latitude} lon=${location.longitude}"
                )
            }
        }
        if (result.accepted && result.pointPersisted) {
            // Committed lat/lon (post-clip for `OUTLIER_CAPPED`) --
            // matches what lands in the database, not the raw chipset
            // coords. Internal snaps advance runtime state without
            // emitting a duplicate point, so they do not reach this log.
            val committed = result.lastFilteredLocation ?: location
            val reason = result.policyMetrics?.reason ?: result.adjustmentReason ?: "accept"
            val source = if (result.adjustmentReason ==
                TrackPointPolicyEngine.ADJUSTMENT_REASON_UNCERTAINTY_SUPPRESSED) {
                "snap"
            } else if (result.adjustmentReason ==
                TrackPointPolicyEngine.ADJUSTMENT_REASON_OUTLIER_CAPPED) {
                "clip"
            } else {
                "live"
            }
            runtimeTelemetry.event(
                name = "track_point",
                details = "lat=%.8f lon=%.8f accuracy=%.1f speed=%.2f reason=%s source=%s".format(
                    committed.latitude,
                    committed.longitude,
                    if (committed.hasAccuracy()) committed.accuracy else -1f,
                    if (committed.hasSpeed()) committed.speed else -1f,
                    reason,
                    source,
                )
            )
        }
        withContext(Dispatchers.Main) { syncRuntimeStateStore() }
        if (!result.accepted) {
            val rejectedForLock = result.rejectReason == TrackPointRejectReason.BAD_ACCURACY ||
                result.rejectReason == TrackPointRejectReason.STALE
            if (rejectedForLock) {
                if (repeatedOutlierSuppressed) {
                    runtimeTelemetry.event(
                        "repeated_outlier_suppressed",
                        "reason=pipeline_pre_freshness repeats=-1 " +
                            "accuracy=${if (location.hasAccuracy()) location.accuracy else -1f} " +
                            "lat=${location.latitude} lon=${location.longitude}"
                    )
                }
                val fastLockSuppressed = repeatedOutlierSuppressed || shouldSuppressFastLockForAutoMotion(
                    rejectReason = result.rejectReason,
                    nowMs = nowMs,
                )
                if (!fastLockSuppressed) {
                    maybeStartFastGpsLockWindow(
                        measuredAccuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                        rejectReason = result.rejectReason
                    )
                }
                if (settings.lowAccuracyFallbackEnabled && !repeatedOutlierSuppressed) {
                    transitionGpsState(GpsRuntimeEvent.FIX_REJECTED, "rejected_for_lock:${result.rejectReason}")
                    lowAccuracyFallbackRejectedFixCountThisSession++
                    maybeLogFallbackRejectSummary(nowMs)
                    lowAccuracyFallbackCandidate = selectLowAccuracyFallbackCandidate(
                        rejectedLocation = location,
                        nowMs = nowMs,
                        motionMode = motionMode,
                    )
                    val armDecision = lowAccuracyFallbackCoordinator.onRejectedFixForLock(
                        fallbackEligible = true,
                        candidateLatitude = location.latitude,
                        candidateLongitude = location.longitude,
                        candidateTimestampMs = location.time
                    )
                    if (armDecision == LowAccuracyFallbackArmDecision.START_TIMER) {
                        transitionGpsState(GpsRuntimeEvent.FALLBACK_TIMER_ARMED, "fallback_timer_armed")
                        lowAccuracyFallbackArmCountThisSession++
                        lowAccuracyFallbackTimerArmedAtMs = nowMs
                        ensureLowAccuracyFallbackTimerRunning()
                    }
                }
            }
            if (bypassFilters || skipAdaptiveTrackingEffects) {
                processAutoTrackingOutput(
                    output = autoTrackingMotionEngine.onRejectedFix(eventTimeMs = nowMs),
                    reason = "rejected_fix"
                )
            }
            broadcastSessionStats()
            lastSpeedReferenceLocation = Location(location)
            return
        }
        if (!isTracking || runGeneration != trackingGeneration) return

        if (!isWaitingForProviderState()) {
            transitionGpsState(GpsRuntimeEvent.FIX_ACCEPTED, "fix_accepted")
        }
        if (result.pointPersisted) {
            pointFreshnessTracker.markLocalPointPersisted(nowMs)
            lowAccuracyFallbackCoordinator.onAcceptedFix()
            cancelLowAccuracyFallbackTimer(clearCandidate = true)
            repeatedOutlierSuppressor.reset()
            freshnessRecoveryController.reset()
            if (lastLoggedPointEmissionTrouble.active) {
                logPointEmissionTroubleTransition(
                    previous = lastLoggedPointEmissionTrouble,
                    current = PointEmissionTrouble.None,
                    nowMs = nowMs,
                )
                lastLoggedPointEmissionTrouble = PointEmissionTrouble.None
            }
            updateRuntimeSnapshot {
                it.copy(
                    lastLocalPointPersistedAtMs = pointFreshnessTracker.lastLocalPointPersistedAtMs,
                    activePointEmissionTrouble = false,
                    activePointEmissionAccuracyTrouble = false,
                    pointEmissionTroubleReason = null,
                )
            }
        } else {
            pointFreshnessTracker.markInternalAccepted(nowMs)
        }
        lastFilteredLocation = result.lastFilteredLocation
        val acceptedLocation = result.lastFilteredLocation ?: location
        if (result.pointPersisted) {
            updateRecoveryAnchor(
                location = acceptedLocation,
                source = "persisted_point",
                motionMode = motionMode,
            )
        }
        val finalPropsJson = pointPropsJson ?: buildLocalPointPropsJson(
            location = acceptedLocation,
            distanceMeters = nextSessionDistance
        )
        updateRuntimeSnapshot {
            it.copy(
                queuedPointsVisible = result.queuedPointsVisible,
                lastTrackedLatitude = result.lastTrackedLatitude,
                lastTrackedLongitude = result.lastTrackedLongitude,
                lastTrackedTimestampMs = result.lastTrackedTimestampMs,
                lastTrackedPropsJson = finalPropsJson
            )
        }
        GeoVaultCaptureLog.d(
            TrackingServiceConstants.TAG,
            "map_update local_fix_accepted track=$selectedTrackerId persisted=${result.pointPersisted} " +
                "runtimeTs=${result.lastTrackedTimestampMs} lat=${result.lastTrackedLatitude} " +
                "lon=${result.lastTrackedLongitude} acc=${result.lastAccuracyMeters} " +
                "queuedVisible=${result.queuedPointsVisible} adjustment=${result.adjustmentReason ?: "none"}"
        )
        val acceptedQuality = result.trackPointQuality ?: resolveTrackPointQuality(acceptedLocation, finalPropsJson)
        if (
            isFastGpsLockWindowActive &&
            hasRecoveredFastGpsLock(
                quality = acceptedQuality,
                measuredAccuracyMeters = result.lastAccuracyMeters,
                accuracyFilterMeters = runtimeContext.effectiveAccuracyThresholdMeters
            )
        ) {
            stopFastGpsLockWindow(reason = "accepted_fix_lock_recovered")
            lowAccuracyFallbackCoordinator.onAcceptedFix()
            cancelLowAccuracyFallbackTimer(clearCandidate = true)
        }
        if (!skipAdaptiveTrackingEffects) {
            val stationaryRadius = runtimeContext.stationaryRadiusMeters
            val adjustmentReason = result.adjustmentReason
            // `UNCERTAINTY_SUPPRESSED` is positive evidence the device hasn't
            // moved (filter snapped to anchor because raw displacement was
            // inside the joint accuracy envelope). Other adjustments
            // (`OUTLIER_CAPPED`, etc.) are pessimistic: hold the counter
            // rather than advance it.
            val filterConfirmedStillness = adjustmentReason ==
                TrackPointPolicyEngine.ADJUSTMENT_REASON_UNCERTAINTY_SUPPRESSED
            val filterIntervened = adjustmentReason != null && !filterConfirmedStillness
            val stationaryConfidence = result.policyMetrics?.stationaryConfidence
            val stationaryReferenceLocation = result.lastFilteredLocation ?: location
            val stationaryDecision = TrackingLocationPolicy.stationaryUpdate(
                lastLocation = stationaryAnchorLocation,
                location = stationaryReferenceLocation,
                stationaryRadiusMeters = stationaryRadius,
                currentConsecutive = consecutiveStationaryPoints,
                significantMotionOnly = settings.significantDataOnly,
                activeMotionHint = activeMotionHint,
                filterIntervened = filterIntervened,
                filterConfirmedStillness = filterConfirmedStillness,
                confidence = stationaryConfidence,
            )
            if (stationaryDecision.reason != "disabled") {
                runtimeTelemetry.event(
                    name = "stationary_update",
                    details = "from=$consecutiveStationaryPoints to=${stationaryDecision.consecutive} " +
                        "shouldPause=${stationaryDecision.shouldPause} reason=${stationaryDecision.reason} " +
                        "accuracy=${if (stationaryReferenceLocation.hasAccuracy()) stationaryReferenceLocation.accuracy else -1f} " +
                        "adjustmentReason=${adjustmentReason ?: "none"} " +
                        "confirmedStillness=$filterConfirmedStillness " +
                        "filterIntervened=$filterIntervened " +
                        "confidence=${stationaryConfidence?.score ?: -1.0} " +
                        "oscillating=${stationaryConfidence?.isOscillating ?: false}"
                )
            }
            consecutiveStationaryPoints = stationaryDecision.consecutive
            stationaryAnchorLocation = when (consecutiveStationaryPoints) {
                0 -> null
                1 -> Location(stationaryReferenceLocation)
                else -> stationaryAnchorLocation
            }
            val pauseEligibility = StationaryPauseEligibilityPolicy.evaluate(
                stationaryPolicyWantsPause = stationaryDecision.shouldPause,
                localPointFresh = pointFreshnessTracker.isLocalFresh(
                    nowMs = nowMs,
                    intervalSec = runtimeContext.pointFreshnessIntervalSec,
                ),
                fallbackPending = lowAccuracyFallbackCoordinator.hasPendingCandidate(),
                providerAvailable = isGpsProviderEnabled(),
            )
            if (stationaryDecision.shouldPause && !pauseEligibility.shouldPause) {
                runtimeTelemetry.event(
                    "stationary_pause_blocked",
                    "reason=${pauseEligibility.reason.telemetryValue} " +
                        "localAgeMs=${pointFreshnessTracker.localPointAgeMs(nowMs) ?: -1L} " +
                        "fallbackPending=${lowAccuracyFallbackCoordinator.hasPendingCandidate()}"
                )
            }
            if (pauseEligibility.shouldPause) {
                enterStationaryRegion(
                    anchorLocation = stationaryAnchorLocation ?: stationaryReferenceLocation,
                    nowMs = nowMs,
                    motionMode = motionMode,
                    radiusMeters = stationaryRadius,
                )
                pauseGps()
            }
            autoTrackingMotionCoordinator.clearEvidenceCandidate()
            // Auto-mode classification runs on vetted geometry only:
            // effectiveDistance / dt from the position filter's accepted,
            // RSS-corrected metrics. Falling back to chipset speed here is
            // what previously let phantom multipath bursts thrash modes.
            val vettedSpeedMps = result.policyMetrics?.let { metrics ->
                if (metrics.elapsedSeconds > 0.0) {
                    (metrics.effectiveDistanceMeters / metrics.elapsedSeconds).toFloat()
                        .coerceAtLeast(0f)
                } else {
                    0f
                }
            } ?: 0f
            processAutoTrackingOutput(
                output = autoTrackingMotionEngine.onAcceptedFix(
                    speedMps = vettedSpeedMps,
                    eventTimeMs = nowMs
                ),
                reason = "accepted_fix"
            )
            maybeApplyElasticDistanceFilter(
                observedSpeedMps = observedSpeedMps,
                measuredAccuracyMeters = (result.lastFilteredLocation ?: location)
                    .takeIf { it.hasAccuracy() }
                    ?.accuracy
            )
        }
        if (result.pointPersisted) {
            GeoVaultCaptureLog.d(
                TrackingServiceConstants.TAG,
                "map_update local_fix_publish_requested track=$selectedTrackerId " +
                    "locationTs=${acceptedLocation.time} lat=${acceptedLocation.latitude} " +
                    "lon=${acceptedLocation.longitude} quality=$acceptedQuality"
            )
            publishTrackPoint(
                trackId = selectedTrackerId,
                location = acceptedLocation,
                propsJson = finalPropsJson,
                quality = acceptedQuality
            )
        } else {
            GeoVaultCaptureLog.d(
                TrackingServiceConstants.TAG,
                "map_update local_fix_no_bus_event track=$selectedTrackerId " +
                    "runtimeTs=${result.lastTrackedTimestampMs} reason=not_persisted"
            )
        }
        lastSpeedReferenceLocation = Location(location)
        withContext(Dispatchers.Main) {
            syncRuntimeStateStore()
            updateNotificationFromDb(broadcastStats = false)
        }
        if (result.pointPersisted) {
            serviceScope.launch(Dispatchers.IO) {
                val outcome = pushQueuedLocations(
                    scope = QueueUploadScope.LIVE_ONLY,
                    updateFailureCounters = false
                )
                if (outcome == SyncFailureClass.NONE) {
                    consecutivePushFailures = 0
                }
            }
        }
    }

    internal suspend fun TrackingServiceHost.processLocationUpdateSerialized(
        location: Location,
        bypassFilters: Boolean = false,
        propsJson: String? = null,
        allowWhenGpsPaused: Boolean = false,
        skipAdaptiveTrackingEffects: Boolean = false,
    ) {
        locationUpdateMutex.withLock {
            processLocationUpdate(
                location = location,
                bypassFilters = bypassFilters,
                propsJson = propsJson,
                allowWhenGpsPaused = allowWhenGpsPaused,
                skipAdaptiveTrackingEffects = skipAdaptiveTrackingEffects,
            )
        }
    }
