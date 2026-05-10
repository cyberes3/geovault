package com.geovault.tracker.policy.filter

import kotlin.math.max

/**
 * Profile-independent positioning filter.
 *
 * One [LocationFilter] instance per stream (e.g. local GPS for the
 * recording user, or per remote-tracker websocket subscription). The
 * pipeline composes:
 *
 *  1. Accuracy threshold gate
 *  2. [LocationMetricsEngine.compute] -> [LocationMetrics] (pure)
 *  3. Motion-resume short-circuit: the first fix after
 *     [onMotionChanged] either snaps to the preserved anchor (false
 *     wakeup) or is accepted verbatim (real movement), skipping the
 *     speed/cap stack against a potentially stale anchor.
 *  4. Implied-speed cap (hard ceiling on raw m/s)
 *  5. Optional 1D Kalman smoothing of the effective displacement,
 *     evaluated *before* the cap comparison so a single-fix magnitude
 *     spike rides the prior down rather than being accepted at face
 *     value
 *  6. Outlier policy switch driven by [LocationFilterConfig.policy]
 *     (PassThrough / Adjust clip / Conservative reject)
 *  7. On accept/adjust only: metrics commit + anchor swap. Rejected
 *     fixes never mutate the anchor or rolling baseline that subsequent
 *     decisions are made against.
 *
 * The filter never mutates its input. Adjusted lat/lon are returned in
 * [LocationFilterResult.adjustedLatitude] / [LocationFilterResult.adjustedLongitude]
 * and the caller must replace the raw geometry before persisting.
 *
 * Thread-safety: this class is *not* internally synchronised. Callers
 * already serialise per-stream ingest (a single coroutine per stream),
 * which is the correct boundary because the filter is stateful and
 * sequential by nature.
 */
class LocationFilter(
    private var config: LocationFilterConfig = LocationFilterConfig.Default,
) {
    private var metricsEngine: LocationMetricsEngine = buildMetricsEngine(config)
    private var kalmanFilter: KalmanFilter = buildKalmanFilter(config)
    private var previousAccepted: LocationInput? = null
    private var pendingMotionResume: Boolean = false

    /**
     * Last fix the filter saw whose accuracy passed the gate, regardless
     * of whether it was accepted, adjusted, or rejected. Used as the
     * `previous` reference for [LocationMetricsEngine.compute] so a
     * single bad fix can only poison one frame's metrics.
     *
     * Distinct from [previousAccepted], which remains the snap-to-anchor
     * target and the basis for committed displacement / rolling window
     * state.
     */
    private var lastSeenFix: LocationInput? = null

    val currentConfig: LocationFilterConfig get() = config

    val lastAcceptedTimestampMs: Long? get() = previousAccepted?.timestampMs

    val lastAcceptedLatLon: Pair<Double, Double>? get() = previousAccepted?.let { it.latitude to it.longitude }

    fun reset() {
        metricsEngine = buildMetricsEngine(config)
        kalmanFilter = buildKalmanFilter(config)
        previousAccepted = null
        lastSeenFix = null
        pendingMotionResume = false
    }

    /**
     * Reconfigure mid-session.
     *
     * Per-fix gates ([LocationFilterConfig.trackingAccuracyThresholdMeters],
     * [LocationFilterConfig.maxFutureSkewMs],
     * [LocationFilterConfig.freshnessTtlMs],
     * [LocationFilterConfig.normalizeSecondsTimestamps]) are evaluated
     * against the live `config` reference inside [evaluate], so updates to
     * those fields take effect on the very next fix without disturbing the
     * filter's anchor or rolling state.
     *
     * Physics-affecting fields (kalman, policy, rolling window, anomaly
     * thresholds) require rebuilding the metrics engine and kalman, so a
     * change there triggers a full [reset]. The classification is owned by
     * [LocationFilterConfig.requiresFilterStateReset].
     */
    fun applyConfig(newConfig: LocationFilterConfig) {
        val oldConfig = config
        config = newConfig
        if (oldConfig.requiresFilterStateReset(newConfig)) {
            reset()
        }
    }

    /**
     * Notify the filter that motion mode changed after a stationary pause.
     * The next fix is treated as a resume boundary: real movement is
     * accepted verbatim, while a false motion wakeup whose fix still
     * overlaps the previous accuracy envelope snaps back to the old anchor.
     *
     * Metrics engine ring buffer is preserved so the rolling window keeps
     * its evidence.
     */
    fun onMotionChanged() {
        kalmanFilter.reset()
        lastSeenFix = null
        pendingMotionResume = previousAccepted != null
    }

    /**
     * Evaluate a single fix.
     *
     * The filter is intentionally motion-agnostic: standstill is decided
     * from the GPS signal itself (RSS-corrected effective distance,
     * reported speed, jerk, stability) rather than from an upstream
     * motion-hint boolean that can lag the chipset state.
     */
    fun evaluate(input: LocationInput): LocationFilterResult {
        val accuracy = input.accuracyMeters?.toDouble()
        // `previous` for the metrics engine is the last fix we *saw*
        // (post accuracy gate), not the last fix we accepted. Keeping
        // `dt`, `raw`, and `impliedSpeed` bounded across reject streaks
        // means a single bad fix can only poison one frame's metrics,
        // never compound.
        val metrics = metricsEngine.compute(current = input, previous = lastSeenFix ?: previousAccepted)

        if (accuracy != null && accuracy > config.trackingAccuracyThresholdMeters) {
            // Skip lastSeenFix update: a 24 km / network-fallback fix
            // is not a usable reference for the next frame's anomaly
            // calculation.
            return LocationFilterResult.rejected(reason = "low-accuracy", metrics = metrics)
        }

        val result = resolveDecision(input = input, metrics = metrics)
        lastSeenFix = input
        return result
    }

    private fun resolveDecision(input: LocationInput, metrics: LocationMetrics): LocationFilterResult {
        val previous = previousAccepted
            ?: return commitAccept(input = input, reason = "first-fix", metrics = metrics)

        if (pendingMotionResume) {
            return resolveMotionResume(input = input, previous = previous, metrics = metrics)
        }

        val capCandidate = inflateCapForAnchorTrust(metrics.capCandidate, metrics.anchorTrust)
            .let { inflateCapForAnomaly(it, metrics.impliedAnomaly) }

        if (metrics.dtSeconds > 0.0 && metrics.impliedSpeedMps > config.maxImpliedSpeedMps) {
            return resolveSpeedSpike(input = input, previous = previous, metrics = metrics)
        }

        // Smooth the RSS-corrected effective distance through the 1D
        // Kalman before comparing against the cap. The smoother absorbs
        // single-fix magnitude spikes that would otherwise read as
        // "within cap but feels wrong". The first-fix shortcut above
        // ensures Kalman state is seeded by an accepted observation, not
        // an arbitrary first measurement.
        val decisionDistance = smoothDecisionDistance(metrics, input)

        return when (config.policy) {
            LocationFilterPolicy.PassThrough ->
                commitAccept(input = input, reason = "pass-through", metrics = metrics)

            LocationFilterPolicy.Adjust ->
                if (decisionDistance <= capCandidate) {
                    commitAccept(input = input, reason = "within-cap", metrics = metrics)
                } else {
                    commitClip(
                        input = input,
                        previous = previous,
                        capMeters = capCandidate,
                        reason = "adjust-cap",
                        metrics = metrics,
                    )
                }

            LocationFilterPolicy.Conservative -> resolveConservative(
                input = input,
                previous = previous,
                capCandidate = capCandidate,
                metrics = metrics,
                decisionDistance = decisionDistance,
            )
        }
    }

    private fun resolveMotionResume(
        input: LocationInput,
        previous: LocationInput,
        metrics: LocationMetrics,
    ): LocationFilterResult {
        pendingMotionResume = false
        if (isNoisyStandstill(metrics)) {
            return snapToAnchor(input = input, previous = previous, metrics = metrics)
        }
        return commitAccept(input = input, reason = "motion-resume", metrics = metrics)
    }

    /**
     * 1D Kalman smoothing of the effective (RSS-corrected) displacement,
     * run before the cap comparison so a single-fix magnitude spike rides
     * the prior down toward the predicted state instead of being accepted
     * at face value.
     *
     * Outlier detection stays on raw distance (see [isOutlier]); smoothing
     * a true teleport could otherwise sneak it under cap.
     */
    private fun smoothDecisionDistance(metrics: LocationMetrics, input: LocationInput): Double {
        if (!config.useKalman) return metrics.effectiveDistanceMeters
        kalmanFilter.configureForSpeed(max(metrics.reportedSpeedMps, metrics.impliedSpeedMps))
        return kalmanFilter.update(
            measurement = metrics.effectiveDistanceMeters,
            accuracyMeters = input.accuracyMeters?.toDouble(),
        )
    }

    private fun resolveConservative(
        input: LocationInput,
        previous: LocationInput,
        capCandidate: Double,
        metrics: LocationMetrics,
        decisionDistance: Double,
    ): LocationFilterResult {
        // Standstill noise is checked before the outlier gate: a phantom
        // jump while sitting still must snap to anchor (Adjusted), not
        // be reported as an outlier (Rejected). Outlier handling is for
        // real-motion teleports the cap couldn't accommodate.
        if (isNoisyStandstill(metrics)) {
            return snapToAnchor(input = input, previous = previous, metrics = metrics)
        }

        // Outlier reject: when raw overshoots `cap * 1.5` we reject --
        // but if `impliedAnomaly` also fires we report it as
        // `implied-speed` to distinguish chipset teleports from
        // slow-but-far fixes.
        if (isOutlier(metrics, capCandidate)) {
            val reason = if (metrics.impliedAnomaly) "implied-speed" else "outlier-capped"
            return LocationFilterResult.rejected(reason = reason, metrics = metrics)
        }

        // [decisionDistance] is `effectiveDistanceMeters` smoothed by the
        // 1D Kalman (see [smoothDecisionDistance]). Strictly <= raw, plus
        // damped against single-fix magnitude spikes, so a brief multipath
        // blip rides the prior down toward the predicted state instead of
        // being accepted at face value. The snap and outlier paths above
        // already handled the truly egregious cases on raw.
        if (decisionDistance <= capCandidate) {
            return commitAccept(input = input, reason = "within-cap", metrics = metrics)
        }

        val capMeters = capCandidate.coerceAtMost(metrics.rawDistanceMeters)
        return commitClip(
            input = input,
            previous = previous,
            capMeters = capMeters,
            reason = "conservative-clip",
            metrics = metrics,
        )
    }

    /**
     * A fix is an outlier when its raw displacement blows past the
     * already-inflated capCandidate by more than [OUTLIER_CAP_MULTIPLIER].
     *
     * Promoted to the first check in [resolveConservative] so a 200 m
     * teleport is rejected before any clip / standstill logic runs.
     */
    private fun isOutlier(metrics: LocationMetrics, capCandidate: Double): Boolean =
        metrics.rawDistanceMeters > capCandidate * OUTLIER_CAP_MULTIPLIER

    /**
     * The "phone is sitting on the table but GPS keeps moving" pattern.
     *
     * Three paths fire, all gated by the chipset itself reporting
     * near-zero motion (`reportedSpeed < REPORTED_MOTION_FLOOR_MPS`).
     * Ordered cheap-to-expensive; the first path that matches wins.
     *
     *  0. **Linear-sum uncertainty absorption**.
     *     `raw <= prevAcc + currAcc`. The linear sum over-states joint
     *     uncertainty relative to RSS (which assumes independent gaussian
     *     errors), but real GPS error is temporally correlated -- so the
     *     linear sum is empirically the right envelope for "did the user
     *     actually move past their own uncertainty cloud?" No buffer-state
     *     dependency, fires immediately on the first fix in a session.
     *
     *  1. **Multi-signal stationary score**.
     *     [LocationMetrics.stationary] is true (or rubber-band
     *     oscillation flagged). The calculator -- gated by
     *     `effective <= 1 m && bufferCount >= 3` -- weighs reported
     *     speed, bearing/speed stability, jerk, and `rawClose`. Catches
     *     the multi-tens-of-meters phantom step that the linear-sum path
     *     can't see when accuracies are tight.
     *
     *  2. **Raw within current-fix accuracy envelope**.
     *     `raw <= currAcc * 1.5`. Current-fix-only fallback for when the
     *     previous accuracy is missing or zero (e.g. mocked / stale
     *     anchor) and path 0 cannot evaluate.
     */
    private fun isNoisyStandstill(metrics: LocationMetrics): Boolean {
        if (metrics.rawDistanceMeters <= 0.0) return false
        if (metrics.reportedSpeedMps >= REPORTED_MOTION_FLOOR_MPS) return false
        val combinedAccuracy = metrics.previousAccuracyMeters + metrics.accuracyMeters
        if (metrics.rawDistanceMeters <= combinedAccuracy) return true
        if (metrics.stationary.isStationary || metrics.stationary.isOscillating) return true
        return metrics.rawDistanceMeters <= metrics.accuracyMeters * RAW_WITHIN_ACCURACY_MULTIPLIER
    }

    private fun resolveSpeedSpike(
        input: LocationInput,
        previous: LocationInput,
        metrics: LocationMetrics,
    ): LocationFilterResult {
        val capMeters = config.maxImpliedSpeedMps * metrics.dtSeconds
        return when (config.policy) {
            LocationFilterPolicy.PassThrough ->
                commitAccept(input = input, reason = "speed-cap-passthrough", metrics = metrics)
            LocationFilterPolicy.Adjust -> commitClip(
                input = input,
                previous = previous,
                capMeters = capMeters,
                reason = "speed-cap",
                metrics = metrics,
            )
            LocationFilterPolicy.Conservative ->
                LocationFilterResult.rejected(reason = "speed-cap-exceeded", metrics = metrics)
        }
    }

    private fun commitAccept(
        input: LocationInput,
        reason: String,
        metrics: LocationMetrics,
    ): LocationFilterResult {
        commit(input = input, metrics = metrics, committedDisplacement = metrics.rawDistanceMeters)
        return LocationFilterResult.accepted(reason = reason, metrics = metrics)
    }

    /**
     * Discard the noisy input and re-commit at the preserved anchor under
     * the canonical `uncertainty-suppressed` reason. Used by both the
     * motion-resume short-circuit and the conservative standstill path.
     */
    private fun snapToAnchor(
        input: LocationInput,
        previous: LocationInput,
        metrics: LocationMetrics,
    ): LocationFilterResult = commitAdjustToAnchor(
        input = input,
        previous = previous,
        reason = "uncertainty-suppressed",
        metrics = metrics,
    )

    private fun commitAdjustToAnchor(
        input: LocationInput,
        previous: LocationInput,
        reason: String,
        metrics: LocationMetrics,
    ): LocationFilterResult {
        val acceptedInput = input.copy(latitude = previous.latitude, longitude = previous.longitude)
        commit(input = acceptedInput, metrics = metrics, committedDisplacement = 0.0)
        return LocationFilterResult.adjusted(
            reason = reason,
            adjustedLatitude = previous.latitude,
            adjustedLongitude = previous.longitude,
            cappedDistanceMeters = 0.0,
            metrics = metrics,
        )
    }

    private fun commitClip(
        input: LocationInput,
        previous: LocationInput,
        capMeters: Double,
        reason: String,
        metrics: LocationMetrics,
    ): LocationFilterResult {
        val raw = metrics.rawDistanceMeters
        if (raw <= 0.0 || capMeters <= 0.0) {
            return commitAdjustToAnchor(input = input, previous = previous, reason = reason, metrics = metrics)
        }
        val scale = (capMeters / raw).coerceIn(0.0, 1.0)
        val adjLat = previous.latitude + (input.latitude - previous.latitude) * scale
        val adjLon = previous.longitude + (input.longitude - previous.longitude) * scale
        val acceptedInput = input.copy(latitude = adjLat, longitude = adjLon)
        commit(input = acceptedInput, metrics = metrics, committedDisplacement = capMeters)
        return LocationFilterResult.adjusted(
            reason = reason,
            adjustedLatitude = adjLat,
            adjustedLongitude = adjLon,
            cappedDistanceMeters = capMeters,
            metrics = metrics,
        )
    }

    private fun commit(
        input: LocationInput,
        metrics: LocationMetrics,
        committedDisplacement: Double,
    ) {
        metricsEngine.commit(
            current = input,
            metrics = metrics,
            committedDisplacementMeters = committedDisplacement,
        )
        // Kalman is mutated up-front in [smoothDecisionDistance]; calling
        // update() again here would double-count the observation and damp
        // the smoother.
        previousAccepted = input
    }

    private fun inflateCapForAnchorTrust(cap: Double, anchorTrust: Double): Double {
        if (anchorTrust >= ANCHOR_TRUST_FULL) return cap
        val ratio = (ANCHOR_TRUST_FULL - anchorTrust) / ANCHOR_TRUST_FULL
        val multiplier = 1.0 + (ratio * ANCHOR_TRUST_INFLATION)
        return cap * multiplier
    }

    /**
     * When [LocationMetrics.impliedAnomaly] fires, inflate the cap by
     * [ANOMALY_CAP_INFLATION]. The boolean already required raw to
     * clear the burst threshold or the speed ceiling, so widening the
     * cap here just gives the outlier-capped reject room to fire on
     * truly egregious overshoots.
     */
    private fun inflateCapForAnomaly(cap: Double, anomaly: Boolean): Double =
        if (anomaly) cap * ANOMALY_CAP_INFLATION else cap

    companion object {
        private fun buildMetricsEngine(config: LocationFilterConfig): LocationMetricsEngine =
            LocationMetricsEngine(
                rollingWindowSeconds = config.rollingWindowSeconds,
                burstWindowSeconds = config.burstWindowSeconds,
                maxImpliedSpeedMps = config.maxImpliedSpeedMps,
                maxBurstDistanceMeters = config.maxBurstDistanceMeters,
            )

        private fun buildKalmanFilter(config: LocationFilterConfig): KalmanFilter =
            KalmanFilter(KalmanTuning.forProfile(config.kalmanProfile))

        private const val OUTLIER_CAP_MULTIPLIER = 1.5
        private const val ANCHOR_TRUST_FULL = 0.7
        private const val ANCHOR_TRUST_INFLATION = 0.5
        private const val ANOMALY_CAP_INFLATION = 1.5
        // The chipset routinely reports 0.6-1.2 m/s of phantom velocity
        // while the device is truly stationary; gating below 1.0 lets
        // the snap paths absorb that band. Slow walking commits via raw
        // distance once accuracy improves enough that the linear-sum
        // envelope is below the user's actual progress.
        private const val REPORTED_MOTION_FLOOR_MPS = 1.0
        private const val RAW_WITHIN_ACCURACY_MULTIPLIER = 1.5
    }
}
