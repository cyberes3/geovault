package com.geovault.tracker.policy.filter

import kotlin.math.max

/**
 * Profile-independent positioning filter.
 *
 * One [LocationFilter] instance per stream (e.g. local GPS for the
 * recording user, or per remote-tracker websocket subscription). The
 * orchestrator composes:
 *
 *  1. Accuracy threshold gate
 *  2. [LocationMetricsEngine.compute] -> [LocationMetrics] (pure)
 *  3. Outlier policy switch driven by [LocationFilterConfig.policy]
 *  4. Final implied-speed cap (hard ceiling)
 *  5. On accept/adjust only: optional Kalman update + metrics commit +
 *     anchor swap. Rejected fixes never mutate internal state, so a
 *     burst of bad fixes cannot pollute the rolling baseline that
 *     subsequent decisions are made against.
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
    private val metricsEngine = LocationMetricsEngine(
        rollingWindowSeconds = config.rollingWindowSeconds,
        burstWindowSeconds = config.burstWindowSeconds,
    )
    private val kalmanFilter = KalmanFilter(KalmanTuning.forProfile(config.kalmanProfile))
    private var previousAccepted: LocationInput? = null

    val currentConfig: LocationFilterConfig get() = config

    val lastAcceptedTimestampMs: Long? get() = previousAccepted?.timestampMs

    val lastAcceptedLatLon: Pair<Double, Double>? get() = previousAccepted?.let { it.latitude to it.longitude }

    fun reset() {
        metricsEngine.reset()
        kalmanFilter.reset()
        previousAccepted = null
    }

    /**
     * Reconfigure mid-session. Resets the internal Kalman + metrics state
     * because tuning constants have changed.
     */
    fun applyConfig(newConfig: LocationFilterConfig) {
        config = newConfig
        reset()
    }

    /**
     * Notify the filter that motion mode (or any other anchor-invalidating
     * condition) has changed. Clears the anchor and resets the Kalman.
     * Metrics engine ring buffer is preserved so the rolling window keeps
     * its evidence.
     */
    fun onMotionChanged() {
        kalmanFilter.reset()
        previousAccepted = null
    }

    /**
     * Evaluate a single fix.
     *
     * The filter is intentionally motion-agnostic (mirrors
     * `tslocationmanager`'s design): standstill is decided from the
     * GPS signal itself (RSS-corrected effective distance, reported
     * speed, jerk, stability) rather than from an upstream motion-hint
     * boolean that can lag the chipset state.
     */
    fun evaluate(input: LocationInput): LocationFilterResult {
        val accuracy = input.accuracyMeters?.toDouble()
        val metrics = metricsEngine.compute(current = input, previous = previousAccepted)

        if (accuracy != null && accuracy > config.trackingAccuracyThresholdMeters) {
            return LocationFilterResult.rejected(reason = "low-accuracy", metrics = metrics)
        }

        val previous = previousAccepted
        if (previous == null) {
            return commitAccept(input = input, reason = "first-fix", metrics = metrics)
        }

        // After a long enough gap of silence the prior anchor is stale.
        // The next fix must be allowed verbatim regardless of policy --
        // applying a speed/burst cap against a 3-minute-old anchor would
        // wrongly reject the natural re-anchor.
        if (metrics.dtSeconds >= LONG_GAP_REANCHOR_SECONDS) {
            return commitAccept(input = input, reason = "long-gap-reanchor", metrics = metrics)
        }

        val capCandidate = inflateCapForAnchorTrust(metrics.capCandidate, metrics.anchorTrust)
            .let { inflateCapForAnomaly(it, metrics.impliedAnomaly) }

        if (metrics.dtSeconds > 0.0 && metrics.impliedSpeedMps > config.maxImpliedSpeedMps) {
            return resolveSpeedSpike(input = input, previous = previous, metrics = metrics)
        }

        if (metrics.burstDistanceMeters > config.maxBurstDistanceMeters &&
            metrics.dtSeconds <= config.burstWindowSeconds
        ) {
            return resolveBurstSpike(input = input, previous = previous, metrics = metrics)
        }

        return when (config.policy) {
            LocationFilterPolicy.PassThrough ->
                commitAccept(input = input, reason = "pass-through", metrics = metrics)

            LocationFilterPolicy.Adjust ->
                if (metrics.rawDistanceMeters <= capCandidate) {
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
            )
        }
    }

    private fun resolveConservative(
        input: LocationInput,
        previous: LocationInput,
        capCandidate: Double,
        metrics: LocationMetrics,
    ): LocationFilterResult {
        // Standstill noise is checked before the outlier gate: a phantom
        // jump while sitting still must snap to anchor (Adjusted), not
        // be reported as an outlier (Rejected). Outlier handling is for
        // real-motion teleports the cap couldn't accommodate.
        if (isNoisyStandstill(metrics)) {
            return commitAdjustToAnchor(
                input = input,
                previous = previous,
                reason = "uncertainty-suppressed",
                metrics = metrics,
            )
        }

        // Severe-anomaly check ahead of within-cap accept, mirroring
        // TS `LocationFilter.a` lines 158-169: an RSS-derived anomaly
        // above 0.85 is rejected outright rather than slipped through
        // the cap test.
        if (metrics.impliedAnomaly >= SEVERE_ANOMALY_THRESHOLD) {
            return LocationFilterResult.rejected(reason = "severe-anomaly", metrics = metrics)
        }

        if (isOutlier(metrics, capCandidate)) {
            return LocationFilterResult.rejected(reason = "outlier-capped", metrics = metrics)
        }

        // Use `effectiveDistanceMeters` (RSS-corrected) for the
        // within-cap test, matching TS lines 123 + 151. `effective` is
        // strictly <= `raw`, so this is more permissive in the
        // high-noise large-displacement regime, but those cases are
        // already handled by the snap and outlier paths above.
        if (metrics.effectiveDistanceMeters <= capCandidate) {
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
     * Two paths fire, both gated by the chipset itself reporting
     * near-zero motion (`reportedSpeed < REPORTED_MOTION_FLOOR_MPS`).
     *
     *  1. **Multi-signal stationary score**.
     *     [LocationMetrics.stationary] is true (or rubber-band
     *     oscillation flagged). The calculator -- gated by TS-style
     *     `effective <= 1 m && bufferCount >= 3` -- weighs reported
     *     speed, bearing/speed stability, jerk, and `rawClose`. Catches
     *     the 38 m phantom step that previously slipped through as
     *     "within-cap".
     *
     *  2. **Raw within accuracy envelope**.
     *     The fix has displacement that fits inside
     *     `accuracyMeters * 1.5` -- TS's `rawClose` signal. The
     *     calculator's strict `effective <= 1 m` gate doesn't classify
     *     this as stationary (effective can be tens of meters during
     *     low-accuracy GPS rubber-banding), but if the chipset reports
     *     we're not moving and the raw distance fits inside the
     *     uncertainty envelope, we should snap rather than commit
     *     phantom motion. This also covers the field-log case
     *     `raw=31.8, acc=26 -> 31.8 <= 26 * 1.5 = 39`.
     */
    private fun isNoisyStandstill(metrics: LocationMetrics): Boolean {
        if (metrics.rawDistanceMeters <= 0.0) return false
        if (metrics.reportedSpeedMps >= REPORTED_MOTION_FLOOR_MPS) return false
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

    private fun resolveBurstSpike(
        input: LocationInput,
        previous: LocationInput,
        metrics: LocationMetrics,
    ): LocationFilterResult {
        return when (config.policy) {
            LocationFilterPolicy.PassThrough ->
                commitAccept(input = input, reason = "burst-passthrough", metrics = metrics)
            LocationFilterPolicy.Adjust -> commitClip(
                input = input,
                previous = previous,
                capMeters = config.maxBurstDistanceMeters,
                reason = "burst-cap",
                metrics = metrics,
            )
            LocationFilterPolicy.Conservative ->
                LocationFilterResult.rejected(reason = "burst-exceeded", metrics = metrics)
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
        if (config.useKalman) {
            kalmanFilter.configureForSpeed(max(metrics.reportedSpeedMps, metrics.impliedSpeedMps))
            kalmanFilter.update(
                measurement = committedDisplacement,
                accuracyMeters = input.accuracyMeters?.toDouble(),
            )
        }
        previousAccepted = input
    }

    private fun inflateCapForAnchorTrust(cap: Double, anchorTrust: Double): Double {
        if (anchorTrust >= ANCHOR_TRUST_FULL) return cap
        val ratio = (ANCHOR_TRUST_FULL - anchorTrust) / ANCHOR_TRUST_FULL
        val multiplier = 1.0 + (ratio * ANCHOR_TRUST_INFLATION)
        return cap * multiplier
    }

    private fun inflateCapForAnomaly(cap: Double, anomaly: Double): Double {
        if (anomaly <= 0.0) return cap
        return cap * (1.0 - (ANOMALY_DEFLATION * anomaly)).coerceAtLeast(0.5)
    }

    companion object {
        private const val LONG_GAP_REANCHOR_SECONDS = 180.0
        private const val OUTLIER_CAP_MULTIPLIER = 1.5
        private const val SEVERE_ANOMALY_THRESHOLD = 0.85
        private const val ANCHOR_TRUST_FULL = 0.7
        private const val ANCHOR_TRUST_INFLATION = 0.5
        private const val ANOMALY_DEFLATION = 0.4
        private const val REPORTED_MOTION_FLOOR_MPS = 0.5
        private const val RAW_WITHIN_ACCURACY_MULTIPLIER = 1.5
    }
}
