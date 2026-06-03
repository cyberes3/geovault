package com.geovault.tracker.positioning.motion
import com.geovault.tracker.positioning.PositioningRuntime
import android.location.Location
import com.geovault.tracker.location.AutoMotionRejectHandling
import com.geovault.tracker.location.AutoTrackingEngineOutput
import com.geovault.tracker.positioning.config.GpsRuntimeState
import com.geovault.tracker.services.LocationIngestResult
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
        rt.motion.processAutoTrackingOutput(
            output = rt.deps.autoTrackingMotionEngine.onTick(nowMs),
            reason = "periodic_decay_tick",
        )
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
        val mode = rt.deps.autoTrackingMotionEngine.snapshot().mode
        val baseDistanceMeters = rt.contextBuilder.effectivePositioningPreset(mode).distanceFilterMeters
        if (baseDistanceMeters <= 0f) return
        val runtimeContext = rt.contextBuilder.currentPositioningRuntimeContext()
        val accuracyThresholdMeters = runtimeContext.effectiveAccuracyThresholdMeters
        if (measuredAccuracyMeters == null || measuredAccuracyMeters > accuracyThresholdMeters) return
        val nextBucket = computeElasticitySpeedBucket(observedSpeedMps)
        val nextDistance = computeElasticDistanceFilterMeters(baseDistanceMeters, nextBucket)
        if (!nextDistance.isFinite() || nextDistance < baseDistanceMeters) return
        val currentDistance = rt.state.elasticDistanceOverrideMeters ?: baseDistanceMeters
        val distanceDelta = kotlin.math.abs(nextDistance - currentDistance)
        if (nextBucket == rt.state.elasticitySpeedBucket && distanceDelta < TrackingServiceConstants.ELASTICITY_REAPPLY_DISTANCE_DELTA_METERS) return
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

    fun computeElasticitySpeedBucket(speedMps: Float?): Int {
        if (speedMps == null || !speedMps.isFinite() || speedMps <= 0f) return 0
        val bucket = kotlin.math.round(speedMps / TrackingServiceConstants.ELASTICITY_SPEED_BUCKET_SIZE_MPS).toInt()
        return bucket.coerceIn(0, TrackingServiceConstants.ELASTICITY_MAX_SPEED_BUCKET)
    }

    fun computeElasticDistanceFilterMeters(baseDistanceMeters: Float, speedBucket: Int): Float {
        val base = baseDistanceMeters.coerceAtLeast(0f)
        if (speedBucket <= 0 || base <= 0f) return base
        val extra = base * TrackingServiceConstants.ELASTICITY_MULTIPLIER * speedBucket.toFloat()
        return (base + extra).coerceAtMost(TrackingServiceConstants.ELASTICITY_MAX_DISTANCE_FILTER_METERS)
    }

}
