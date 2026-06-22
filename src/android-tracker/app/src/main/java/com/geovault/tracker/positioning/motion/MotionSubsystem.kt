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
import com.geovault.tracker.logging.GeoVaultPointRecordingLog
import com.geovault.tracker.policy.filter.StationaryConfidence
import com.geovault.tracker.sensor.ImuClassification
import com.geovault.tracker.sensor.ImuMotionContext
import com.geovault.tracker.services.LocationIngestResult
import com.geovault.tracker.services.TrackingMotionMode
import com.geovault.tracker.settings.TrackerSettings
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class MotionSubsystem(private val rt: PositioningRuntime) {

    /**
     * Most recent stable IMU classification. Written by [onImuMotionUpdate] on the main thread
     * (sensor callbacks are routed via [android.os.Handler] to the main looper), and read on the
     * same thread inside [handleAcceptedAdaptiveTrackingEffects]. @Volatile defends against any
     * future threading changes without requiring explicit synchronisation.
     */
    @Volatile private var currentImuContext: ImuMotionContext? = null

    /**
     * Called by [ImuMotionClassifier] on every stable classification emission (~every 15 s).
     *
     * Updates the local IMU context for use in [handleAcceptedAdaptiveTrackingEffects], logs the
     * classification, and activates the attention boost when IMU disagrees with the current GPS
     * mode. The boost tightens the GPS location request so evidence accumulates faster to confirm
     * or disprove the IMU observation. GPS mode selection itself is unaffected — [AutoTrackingMotionEngine]
     * has no knowledge of IMU data.
     */
    fun onImuMotionUpdate(ctx: ImuMotionContext) {
        val previousClassification = currentImuContext?.classification
        currentImuContext = ctx
        val nowMs = rt.deps.clock.wallTimeMs()
        rt.deps.runtimeTelemetry.event(
            name = "imu_classification",
            details = "class=${ctx.classification} confidence=${ctx.confidence} " +
                "variance=${ctx.accelerationVarianceMps4} stepRate=${ctx.stepRatePerMinute}",
        )
        GeoVaultPointRecordingLog.i(
            IMU_RECORDING_TAG,
            "positioning_imu_classification " +
                "track=${rt.ports.selectedTrackerId()} " +
                "wall=$nowMs " +
                "elapsedNanos=${rt.deps.clock.elapsedRealtimeNanos()} " +
                "class=${ctx.classification.name} " +
                "confidence=${ctx.confidence} " +
                "variance=${ctx.accelerationVarianceMps4} " +
                "stepRate=${ctx.stepRatePerMinute}",
        )

        val currentMode = rt.contextBuilder.resolveActiveMotionMode()
        val transitionBoost = computeTransitionBoostNeeded(
            previousClassification = previousClassification,
            newClassification = ctx.classification,
            lastTransitionBoostAtMs = rt.state.lastImuTransitionBoostAtMs,
            nowMs = nowMs,
        )
        if (transitionBoost) {
            rt.state.lastImuTransitionBoostAtMs = nowMs
        }
        val needsBoost = computeBoostNeeded(ctx.classification, currentMode) || transitionBoost
        val changed = rt.state.imuAttentionBoostActive != needsBoost
        rt.state.imuAttentionBoostActive = needsBoost
        if (changed) {
            rt.deps.runtimeTelemetry.event(
                name = "imu_attention_boost",
                details = "active=$needsBoost imu=${ctx.classification} mode=$currentMode transition=$transitionBoost",
            )
            rt.locationRequests.reapplyLocationRequestIfActive("imu_attention_boost")
        }

        // When the IMU reports VEHICULAR with sufficient confidence and GPS is currently
        // paused, wake it immediately rather than waiting for the hardware significant-motion
        // sensor, which can be delayed by several minutes.  A post-wake cooldown is applied
        // to prevent SNAP_INTERNAL GPS fixes at the previous parked location from immediately
        // re-pausing GPS before actual vehicle displacement is detected.
        if (computeVehicularWakeNeeded(
                previousClassification = previousClassification,
                newClassification = ctx.classification,
                confidence = ctx.confidence,
                lastWakeAtMs = rt.state.lastImuVehicularWakeAtMs,
                nowMs = nowMs,
                isPaused = rt.state.gpsRuntimeState == GpsRuntimeState.PAUSED_FOR_MOTION,
            )
        ) {
            rt.state.lastImuVehicularWakeAtMs = nowMs
            val cooldownUntilMs = nowMs + TrackingLocationPolicy.IMU_VEHICULAR_WAKE_COOLDOWN_MS
            rt.state.stationaryPauseCooldownUntilMs = maxOf(
                rt.state.stationaryPauseCooldownUntilMs,
                cooldownUntilMs,
            )
            rt.deps.runtimeTelemetry.event(
                name = "imu_vehicular_wake",
                details = "confidence=${ctx.confidence} cooldownUntil=$cooldownUntilMs",
            )
            rt.collection.resumeGps("imu_vehicular_wake")
        }
    }

    /**
     * Clears IMU state that is specific to a single tracking session. Called at session start
     * so stale context from the previous session cannot influence stationary detection during
     * the new session's first ~15 s (before [ImuMotionClassifier] re-stabilizes).
     */
    fun clearSessionImuState() {
        currentImuContext = null
    }

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

    fun handleAcceptedAdaptiveTrackingEffects(
        result: LocationIngestResult,
        rawLocation: Location,
        runtimeContext: PositioningContext,
        settings: TrackerSettings,
        motionMode: TrackingMotionMode,
        activeSpeedHint: Boolean,
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
        val imuClassification = currentImuContext?.classification
        val inMotionCooldown = nowMs < rt.state.stationaryPauseCooldownUntilMs
        if (inMotionCooldown) {
            rt.deps.runtimeTelemetry.event(
                name = "stationary_motion_cooldown",
                details = "remainingMs=${rt.state.stationaryPauseCooldownUntilMs - nowMs}"
            )
        }
        // After an imu_vehicular_wake, suppress the IMU=STATIONARY arm of
        // confidence_fast_advance for IMU_FAST_ADVANCE_COOLDOWN_MS. The IMU can
        // oscillate back to STATIONARY within one classification window after a
        // vehicular event, and a single such cycle must not immediately re-pause GPS.
        val imuFastAdvanceCooldown = rt.state.lastImuVehicularWakeAtMs > 0L &&
            nowMs - rt.state.lastImuVehicularWakeAtMs < TrackingLocationPolicy.IMU_FAST_ADVANCE_COOLDOWN_MS
        val stationaryDecision = TrackingLocationPolicy.stationaryUpdate(
            lastLocation = rt.state.stationaryAnchorLocation,
            location = stationaryReferenceLocation,
            stationaryRadiusMeters = stationaryRadius,
            currentConsecutive = rt.state.consecutiveStationaryPoints,
            significantMotionOnly = settings.significantDataOnly,
            activeSpeedHint = activeSpeedHint,
            filterIntervened = filterIntervened,
            filterConfirmedStillness = filterConfirmedStillness,
            confidence = stationaryConfidence,
            imuClassification = imuClassification,
            inMotionCooldown = inMotionCooldown,
            imuFastAdvanceCooldown = imuFastAdvanceCooldown,
        )
        if (stationaryDecision.reason != "disabled" && stationaryDecision.reason != "motion_exit_cooldown") {
            rt.deps.runtimeTelemetry.event(
                name = "stationary_update",
                details = "from=${rt.state.consecutiveStationaryPoints} to=${stationaryDecision.consecutive} " +
                    "shouldPause=${stationaryDecision.shouldPause} reason=${stationaryDecision.reason} " +
                    "accuracy=${if (stationaryReferenceLocation.hasAccuracy()) stationaryReferenceLocation.accuracy else -1f} " +
                    "adjustmentReason=${adjustmentReason ?: "none"} " +
                    "confirmedStillness=$filterConfirmedStillness " +
                    "filterIntervened=$filterIntervened " +
                    "confidence=${stationaryConfidence?.score ?: -1.0} " +
                    "oscillating=${stationaryConfidence?.isOscillating ?: false} " +
                    "imu=${imuClassification ?: "none"}"
            )
        }
        rt.state.consecutiveStationaryPoints = stationaryDecision.consecutive
        rt.state.stationaryAnchorLocation = when (rt.state.consecutiveStationaryPoints) {
            0 -> null
            1 -> Location(stationaryReferenceLocation)
            else -> rt.state.stationaryAnchorLocation
        }
        val sensorFusionHighConfidence = computeSensorFusionHighConfidence(
            stationaryConfidence = stationaryConfidence,
            imuClassification = currentImuContext?.classification,
        )
        val pauseEligibility = StationaryPauseEligibilityPolicy.evaluate(
            stationaryPolicyWantsPause = stationaryDecision.shouldPause,
            localPointFresh = localPointFresh,
            fallbackPending = rt.deps.lowAccuracyFallbackCoordinator.hasPendingCandidate(),
            providerAvailable = rt.utilities.isGpsProviderEnabled(),
            sensorFusionHighConfidence = sensorFusionHighConfidence,
        )
        if (stationaryDecision.shouldPause && !pauseEligibility.shouldPause) {
            rt.deps.runtimeTelemetry.event(
                "stationary_pause_blocked",
                "reason=${pauseEligibility.reason.telemetryValue} " +
                    "localAgeMs=${rt.deps.pointFreshnessTracker.localPointAgeMs(nowMs) ?: -1L} " +
                    "fallbackPending=${rt.deps.lowAccuracyFallbackCoordinator.hasPendingCandidate()}"
            )
        }
        val gpsBeingPaused = pauseEligibility.shouldPause
        if (gpsBeingPaused) {
            rt.collection.enterStationaryRegion(
                anchorLocation = rt.state.stationaryAnchorLocation ?: stationaryReferenceLocation,
                nowMs = nowMs,
                motionMode = motionMode,
                radiusMeters = stationaryRadius,
            )
            rt.collection.pauseGps()
        }
        rt.deps.autoTrackingMotionCoordinator.clearEvidenceCandidate()
        if (!gpsBeingPaused) {
            // When GPS is being paused, pauseGps() already called onGpsPaused() internally.
            // Calling onAcceptedFix(0) here would override that signal and feed a zero-speed
            // sample into the smoother, corrupting the consecutive-demotion streak.
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
        }
        rt.motion.maybeApplyElasticDistanceFilter(
            observedSpeedMps = observedSpeedMps,
            measuredAccuracyMeters = (result.lastFilteredLocation ?: rawLocation)
                .takeIf { it.hasAccuracy() }
                ?.accuracy
        )
    }

    fun processAutoTrackingOutput(output: AutoTrackingEngineOutput, reason: String) {
        if (output.modeChanged) {
            // Clear the IMU attention boost on any GPS mode change — the GPS system has
            // now confirmed or resolved whatever the IMU hint was pointing to. The next
            // IMU heartbeat (~15 s) will re-evaluate and re-arm the boost if still needed.
            rt.state.imuAttentionBoostActive = false
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

    companion object {
        private const val IMU_RECORDING_TAG = "ImuRecording"

        /**
         * Returns whether an IMU attention boost should be active given [classification] and
         * [currentMode]. A boost is needed whenever the IMU observation disagrees with the
         * current GPS mode, prompting the GPS system to gather evidence faster.
         *
         * Exposed as `internal` so unit tests can verify all boost-trigger combinations without
         * constructing a full [PositioningRuntime].
         */
        internal fun computeBoostNeeded(
            classification: ImuClassification,
            currentMode: TrackingMotionMode,
        ): Boolean = when (classification) {
            ImuClassification.PEDESTRIAN -> currentMode != TrackingMotionMode.WALKING
            ImuClassification.VEHICULAR  -> currentMode == TrackingMotionMode.WALKING
            else -> false
        }

        /**
         * Returns whether the IMU classification transition itself warrants an attention boost.
         * Any shift between classifications is evidence the motion environment just changed,
         * so GPS should sample aggressively to confirm the new state regardless of whether
         * the new classification disagrees with the current GPS mode.
         *
         * [previousClassification] null means this is the first emission of the session — no
         * transition has occurred so no boost is warranted. The [lastTransitionBoostAtMs]
         * debounce prevents persistent oscillation (e.g. PEDESTRIAN→UNKNOWN→PEDESTRIAN…)
         * from re-arming the boost on every classifier cycle.
         *
         * Exposed as `internal` so unit tests can verify all cases without constructing a
         * full [PositioningRuntime].
         */
        internal fun computeTransitionBoostNeeded(
            previousClassification: ImuClassification?,
            newClassification: ImuClassification,
            lastTransitionBoostAtMs: Long,
            nowMs: Long,
        ): Boolean {
            if (previousClassification == null) return false
            if (previousClassification == newClassification) return false
            return nowMs - lastTransitionBoostAtMs >= TrackingLocationPolicy.IMU_TRANSITION_BOOST_DEBOUNCE_MS
        }

        /**
         * Returns whether sensor fusion evidence is high-confidence enough to bypass the
         * [com.geovault.tracker.location.StationaryPauseEligibilityPolicy.STALE_LOCAL_POINT] gate.
         *
         * [ImuClassification.STATIONARY] satisfies this independently of GPS-derived
         * [stationaryConfidence]: the inertial sensor has confirmed no movement without any
         * GPS measurement required, making a stale committed trail irrelevant.
         *
         * Exposed as `internal` so unit tests can verify this path directly.
         */
        internal fun computeSensorFusionHighConfidence(
            stationaryConfidence: StationaryConfidence?,
            imuClassification: ImuClassification?,
        ): Boolean = TrackingLocationPolicy.isHighConfidence(stationaryConfidence) ||
            imuClassification == ImuClassification.STATIONARY

        /**
         * Returns whether an IMU-driven GPS wake-from-pause should be triggered.
         *
         * A wake fires only when:
         *  - GPS is currently paused for motion ([isPaused] true)
         *  - The current classification is [ImuClassification.VEHICULAR] (sustained or freshly
         *    transitioned — the previous value is intentionally not checked)
         *  - The classification carries at least [TrackingLocationPolicy.IMU_VEHICULAR_WAKE_MIN_CONFIDENCE]
         *  - Enough time has elapsed since the last wake to satisfy the debounce window
         *
         * The `previousClassification` parameter is retained for call-site symmetry but is not
         * used in the decision. Requiring a fresh *transition* was too restrictive: when GPS
         * pauses while VEHICULAR is already active, the next IMU tick (~15 s later) would never
         * satisfy a transition guard, leaving GPS paused for minutes. The
         * [TrackingLocationPolicy.IMU_VEHICULAR_WAKE_DEBOUNCE_MS] window is sufficient to prevent
         * rapid-fire wakes.
         *
         * Exposed as `internal` so unit tests can verify all cases without constructing a
         * full [PositioningRuntime].
         */
        internal fun computeVehicularWakeNeeded(
            previousClassification: ImuClassification?,
            newClassification: ImuClassification,
            confidence: Float,
            lastWakeAtMs: Long,
            nowMs: Long,
            isPaused: Boolean,
        ): Boolean {
            if (!isPaused) return false
            if (newClassification != ImuClassification.VEHICULAR) return false
            if (confidence < TrackingLocationPolicy.IMU_VEHICULAR_WAKE_MIN_CONFIDENCE) return false
            return nowMs - lastWakeAtMs >= TrackingLocationPolicy.IMU_VEHICULAR_WAKE_DEBOUNCE_MS
        }
    }
}
