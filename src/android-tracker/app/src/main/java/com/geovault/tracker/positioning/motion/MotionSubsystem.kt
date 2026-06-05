package com.geovault.tracker.positioning.motion
import com.geovault.tracker.positioning.PositioningRuntime
import android.location.Location
import com.geovault.tracker.TrackingLocationPolicy
import com.geovault.tracker.location.AutoMotionRejectHandling
import com.geovault.tracker.location.AutoTrackingEngineOutput
import com.geovault.tracker.location.StationaryPauseEligibilityPolicy
import com.geovault.tracker.policy.TrackPointEmissionDecision
import com.geovault.tracker.policy.TrackPointPolicyEngine
import com.geovault.tracker.positioning.PositioningContext
import com.geovault.tracker.positioning.config.GpsRuntimeState
import com.geovault.tracker.positioning.config.PositioningElasticityConfig
import com.geovault.tracker.services.LocationIngestResult
import com.geovault.tracker.services.TrackingMotionMode
import com.geovault.tracker.settings.TrackerSettings
import com.geovault.tracker.tracking.TrackingServiceConstants
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class MotionSubsystem(private val rt: PositioningRuntime) {
    fun startAutoModeTickIfNeeded() {
        if (!rt.state.isTracking) return
        if (rt.state.autoModeTickJob?.isActive == true) return
        rt.state.autoModeTickJob = rt.serviceScope.launch {
            while (rt.state.isTracking) {
                delay(5_000L)
                rt.motion.processAutoModeTick()
            }
        }
    }

    fun processAutoModeTick(nowMs: Long = rt.deps.clock.wallTimeMs()) {
        processAarScrutinyWindow(nowMs)
        rt.motion.processAutoTrackingOutput(
            output = rt.deps.autoTrackingMotionEngine.onTick(nowMs),
            reason = "periodic_decay_tick",
        )
    }

    private fun processAarScrutinyWindow(nowMs: Long) {
        val hint = rt.deps.activityHintSource?.currentHint(nowMs)
        when {
            hint != null -> {
                val newUntilMs = nowMs + TrackingServiceConstants.AAR_SCRUTINY_WINDOW_MS
                if (newUntilMs > rt.state.aarScrutinyWindowUntilMs) {
                    val wasAlreadyOpen = rt.state.aarScrutinyWindowUntilMs > nowMs
                    rt.state.aarScrutinyWindowUntilMs = newUntilMs
                    when {
                        rt.state.gpsRuntimeState == GpsRuntimeState.PAUSED_FOR_MOTION -> {
                            val gpsStateBeforeResume = rt.state.gpsRuntimeState
                            rt.collection.resumeGps("aar_hint")
                            rt.deps.runtimeTelemetry.event(
                                "aar_stationary_resumed",
                                "gpsState=$gpsStateBeforeResume",
                            )
                        }
                        wasAlreadyOpen -> {
                            rt.deps.runtimeTelemetry.event(
                                "aar_scrutiny_extended",
                                "untilMs=$newUntilMs",
                            )
                        }
                        else -> {
                            val applied = rt.locationRequests.applyCurrentLocationRequest("aar_scrutiny_enter")
                            if (applied) {
                                rt.deps.runtimeTelemetry.event(
                                    "aar_scrutiny_opened",
                                    "gpsState=${rt.state.gpsRuntimeState} intervalSec=${TrackingServiceConstants.AAR_SCRUTINY_INTERVAL_SEC}",
                                )
                            } else {
                                rt.deps.runtimeTelemetry.event(
                                    "aar_scrutiny_open_deferred",
                                    "gpsState=${rt.state.gpsRuntimeState}",
                                )
                            }
                        }
                    }
                }
            }
            rt.state.aarScrutinyWindowUntilMs > 0L && nowMs >= rt.state.aarScrutinyWindowUntilMs -> {
                rt.state.aarScrutinyWindowUntilMs = 0L
                rt.deps.runtimeTelemetry.event("aar_scrutiny_expired", "")
                rt.locationRequests.reapplyLocationRequestIfActive("aar_scrutiny_exit")
            }
        }
    }

    fun stopAutoModeTick() {
        rt.state.autoModeTickJob?.cancel()
        rt.state.autoModeTickJob = null
    }

    fun handleAutoMotionRejectedFix(
        result: LocationIngestResult,
        location: Location,
        nowMs: Long,
    ): AutoMotionRejectHandling {
        val handling = rt.deps.autoTrackingMotionCoordinator.onRejectedOrHeld(
            metrics = result.policyMetrics,
            rejectReason = result.rejectReason,
            eventTimeMs = location.time,
            nowMs = nowMs,
        )
        when (handling) {
            is AutoMotionRejectHandling.Evidence -> {
                rt.deps.runtimeTelemetry.event(
                    name = "auto_motion_evidence",
                    details = "modeBefore=${handling.modeBefore} modeAfter=${handling.output.state.mode} " +
                        "reason=${handling.policyReason ?: "unknown"} speed=${handling.evidence.speedMps} " +
                        "accuracy=${handling.accuracyMeters ?: -1f} dt=${handling.elapsedSeconds} " +
                        "path=${handling.evidence.path}"
                )
                rt.motion.processAutoTrackingOutput(
                    output = handling.output,
                    reason = "motion_evidence_${handling.policyReason ?: "unknown"}",
                )
            }
            is AutoMotionRejectHandling.Preserved -> {
                rt.deps.runtimeTelemetry.event(
                    name = "auto_motion_streak_preserved",
                    details = "rejectReason=${handling.rejectReason} policyReason=${handling.policyReason ?: "none"} " +
                        "elapsedSinceCapEvidenceMs=${handling.elapsedSinceCapEvidenceMs}"
                )
            }
            is AutoMotionRejectHandling.Rejected -> {
                rt.motion.processAutoTrackingOutput(
                    output = handling.output,
                    reason = "rejected_fix"
                )
            }
        }
        return handling
    }

    fun handleAcceptedAdaptiveTrackingEffects(
        result: LocationIngestResult,
        rawLocation: Location,
        runtimeContext: PositioningContext,
        settings: TrackerSettings,
        motionMode: TrackingMotionMode,
        activeMotionHint: Boolean,
        observedSpeedMps: Float?,
        nowMs: Long,
    ) {
        val stationaryRadius = runtimeContext.stationaryRadiusMeters
        val adjustmentReason = result.adjustmentReason
        val localPointFresh = rt.deps.pointFreshnessTracker.isLocalFresh(
            nowMs = nowMs,
            intervalSec = runtimeContext.pointFreshnessIntervalSec,
        )
        val filterConfirmedStillness = result.emissionDecision == TrackPointEmissionDecision.SNAP_INTERNAL &&
            adjustmentReason == TrackPointPolicyEngine.ADJUSTMENT_REASON_UNCERTAINTY_SUPPRESSED &&
            localPointFresh
        val filterIntervened = adjustmentReason != null && !filterConfirmedStillness
        val stationaryConfidence = result.policyMetrics?.stationaryConfidence
        val stationaryReferenceLocation = result.lastFilteredLocation ?: rawLocation
        val stationaryDecision = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = rt.state.stationaryAnchorLocation,
            location = stationaryReferenceLocation,
            stationaryRadiusMeters = stationaryRadius,
            currentConsecutive = rt.state.consecutiveStationaryPoints,
            significantMotionOnly = settings.significantDataOnly,
            activeMotionHint = activeMotionHint,
            filterIntervened = filterIntervened,
            filterConfirmedStillness = filterConfirmedStillness,
            confidence = stationaryConfidence,
        )
        if (stationaryDecision.reason != "disabled") {
            rt.deps.runtimeTelemetry.event(
                name = "stationary_update",
                details = "from=${rt.state.consecutiveStationaryPoints} to=${stationaryDecision.consecutive} " +
                    "shouldPause=${stationaryDecision.shouldPause} reason=${stationaryDecision.reason} " +
                    "accuracy=${if (stationaryReferenceLocation.hasAccuracy()) stationaryReferenceLocation.accuracy else -1f} " +
                    "adjustmentReason=${adjustmentReason ?: "none"} " +
                    "confirmedStillness=$filterConfirmedStillness " +
                    "filterIntervened=$filterIntervened " +
                    "confidence=${stationaryConfidence?.score ?: -1.0} " +
                    "oscillating=${stationaryConfidence?.isOscillating ?: false}"
            )
        }
        rt.state.consecutiveStationaryPoints = stationaryDecision.consecutive
        rt.state.stationaryAnchorLocation = when (rt.state.consecutiveStationaryPoints) {
            0 -> null
            1 -> Location(stationaryReferenceLocation)
            else -> rt.state.stationaryAnchorLocation
        }
        val pauseEligibility = StationaryPauseEligibilityPolicy.evaluate(
            stationaryPolicyWantsPause = stationaryDecision.shouldPause,
            localPointFresh = localPointFresh,
            fallbackPending = rt.deps.lowAccuracyFallbackCoordinator.hasPendingCandidate(),
            providerAvailable = rt.utilities.isGpsProviderEnabled(),
        )
        if (stationaryDecision.shouldPause && !pauseEligibility.shouldPause) {
            rt.deps.runtimeTelemetry.event(
                "stationary_pause_blocked",
                "reason=${pauseEligibility.reason.telemetryValue} " +
                    "localAgeMs=${rt.deps.pointFreshnessTracker.localPointAgeMs(nowMs) ?: -1L} " +
                    "fallbackPending=${rt.deps.lowAccuracyFallbackCoordinator.hasPendingCandidate()}"
            )
        }
        if (pauseEligibility.shouldPause) {
            rt.collection.enterStationaryRegion(
                anchorLocation = rt.state.stationaryAnchorLocation ?: stationaryReferenceLocation,
                nowMs = nowMs,
                motionMode = motionMode,
                radiusMeters = stationaryRadius,
            )
            rt.collection.pauseGps()
        }
        rt.deps.autoTrackingMotionCoordinator.clearEvidenceCandidate()
        val vettedSpeedMps = result.policyMetrics?.let { metrics ->
            if (metrics.elapsedSeconds > 0.0) {
                (metrics.effectiveDistanceMeters / metrics.elapsedSeconds).toFloat()
                    .coerceAtLeast(0f)
            } else {
                0f
            }
        } ?: 0f
        rt.motion.processAutoTrackingOutput(
            output = rt.deps.autoTrackingMotionEngine.onAcceptedFix(
                speedMps = vettedSpeedMps,
                eventTimeMs = nowMs
            ),
            reason = "accepted_fix"
        )
        rt.motion.maybeApplyElasticDistanceFilter(
            observedSpeedMps = observedSpeedMps,
            measuredAccuracyMeters = (result.lastFilteredLocation ?: rawLocation)
                .takeIf { it.hasAccuracy() }
                ?.accuracy
        )
    }

    fun processAutoTrackingOutput(output: AutoTrackingEngineOutput, reason: String) {
        if (output.modeChanged) {
            rt.state.lastAutoModeChangedAtMs = rt.deps.clock.wallTimeMs()
            rt.motion.resetElasticDistanceOverride(reason = "auto_mode_changed_$reason", reapplyRequest = false)
            rt.locationRequests.reapplyLocationRequestIfActive("auto_mode_$reason")
            rt.deps.runtimeTelemetry.event(
                name = "auto_mode_changed",
                details = "mode=${output.state.mode} reason=$reason path=${output.transitionPath}"
            )
        }
        rt.projection.syncRuntimeStateStore()
    }

    fun maybeApplyElasticDistanceFilter(observedSpeedMps: Float?, measuredAccuracyMeters: Float?) {
        if (!rt.state.isTracking) return
        if (
            rt.state.gpsRuntimeState == GpsRuntimeState.PAUSED_FOR_MOTION ||
            rt.state.gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER ||
            rt.state.gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
        ) {
            return
        }
        if (rt.state.isFastGpsLockWindowActive) return
        val runtimeContext = rt.contextBuilder.currentPositioningRuntimeContext()
        val baseDistanceMeters = runtimeContext.baseDistanceFilterMeters
        if (baseDistanceMeters <= 0f) return
        val accuracyThresholdMeters = runtimeContext.effectiveAccuracyThresholdMeters
        if (measuredAccuracyMeters == null || measuredAccuracyMeters > accuracyThresholdMeters) return
        val elasticityConfig = runtimeContext.elasticityConfig
        val nextBucket = computeElasticitySpeedBucket(observedSpeedMps, elasticityConfig)
        val nextDistance = computeElasticDistanceFilterMeters(baseDistanceMeters, nextBucket, elasticityConfig)
        if (!nextDistance.isFinite() || nextDistance < baseDistanceMeters) return
        val currentDistance = rt.state.elasticDistanceOverrideMeters ?: baseDistanceMeters
        val distanceDelta = kotlin.math.abs(nextDistance - currentDistance)
        if (nextBucket == rt.state.elasticitySpeedBucket &&
            distanceDelta < elasticityConfig.reapplyDistanceDeltaMeters
        ) return
        rt.state.elasticitySpeedBucket = nextBucket
        rt.state.elasticDistanceOverrideMeters = if (nextBucket > 0) nextDistance else null
        rt.locationRequests.reapplyLocationRequestIfActive("elasticity_update")
        rt.deps.runtimeTelemetry.decision(
            name = "elasticity_updated",
            details = "base=$baseDistanceMeters speed=${observedSpeedMps ?: -1f} bucket=$nextBucket distance=$nextDistance"
        )
    }

    fun resetElasticDistanceOverride(reason: String, reapplyRequest: Boolean) {
        val changed = rt.state.elasticDistanceOverrideMeters != null || rt.state.elasticitySpeedBucket != 0
        rt.state.elasticDistanceOverrideMeters = null
        rt.state.elasticitySpeedBucket = 0
        if (changed) {
            rt.deps.runtimeTelemetry.event("elasticity_reset", "reason=$reason")
        }
        if (reapplyRequest) {
            rt.locationRequests.reapplyLocationRequestIfActive("elasticity_reset_$reason")
        }
    }

    fun computeElasticitySpeedBucket(
        speedMps: Float?,
        config: PositioningElasticityConfig = PositioningElasticityConfig.Default,
    ): Int {
        if (speedMps == null || !speedMps.isFinite() || speedMps <= 0f) return 0
        val bucket = kotlin.math.round(speedMps / config.speedBucketSizeMps).toInt()
        return bucket.coerceIn(0, config.maxSpeedBucket)
    }

    fun computeElasticDistanceFilterMeters(
        baseDistanceMeters: Float,
        speedBucket: Int,
        config: PositioningElasticityConfig = PositioningElasticityConfig.Default,
    ): Float {
        val base = baseDistanceMeters.coerceAtLeast(0f)
        if (speedBucket <= 0 || base <= 0f) return base
        val extra = base * config.multiplier * speedBucket.toFloat()
        return (base + extra).coerceAtMost(config.maxDistanceFilterMeters)
    }

}
