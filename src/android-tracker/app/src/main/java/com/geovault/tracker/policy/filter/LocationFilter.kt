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
        val noReportedMotion = metrics.reportedSpeedMps < REPORTED_MOTION_FLOOR_MPS
        val tightAccuracyStationaryJitter =
            metrics.dtSeconds <= STATIONARY_JITTER_MAX_DT_SECONDS &&
                metrics.rawDistanceMeters <= max(
                    STATIONARY_JITTER_MIN_RAW_METERS,
                    metrics.accuracyMeters * STATIONARY_JITTER_RAW_ACCURACY_MULTIPLIER
                ) &&
                metrics.effectiveDistanceMeters <= max(
                    STATIONARY_JITTER_MIN_EFFECTIVE_METERS,
                    metrics.accuracyMeters * STATIONARY_JITTER_EFFECTIVE_ACCURACY_MULTIPLIER
                )
        val noisyStandstill = (metrics.effectiveDistanceMeters <= 0.0 || tightAccuracyStationaryJitter) &&
            metrics.rawDistanceMeters > 0.0 &&
            noReportedMotion &&
            (metrics.isOscillating || metrics.isStationary)
        if (noisyStandstill) {
            return commitAdjustToAnchor(
                input = input,
                previous = previous,
                reason = "uncertainty-suppressed",
                metrics = metrics,
            )
        }

        if (metrics.rawDistanceMeters <= capCandidate) {
            return commitAccept(input = input, reason = "within-cap", metrics = metrics)
        }

        if (metrics.impliedAnomaly >= SEVERE_ANOMALY_THRESHOLD) {
            return LocationFilterResult.rejected(reason = "severe-anomaly", metrics = metrics)
        }

        if (metrics.rawDistanceMeters > capCandidate * CONSERVATIVE_REJECT_RATIO) {
            return LocationFilterResult.rejected(reason = "cap-exceeded", metrics = metrics)
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
        private const val CONSERVATIVE_REJECT_RATIO = 1.5
        private const val SEVERE_ANOMALY_THRESHOLD = 0.85
        private const val ANCHOR_TRUST_FULL = 0.7
        private const val ANCHOR_TRUST_INFLATION = 0.5
        private const val ANOMALY_DEFLATION = 0.4
        private const val REPORTED_MOTION_FLOOR_MPS = 0.5
        private const val STATIONARY_JITTER_MAX_DT_SECONDS = 5.0
        private const val STATIONARY_JITTER_MIN_RAW_METERS = 12.0
        private const val STATIONARY_JITTER_RAW_ACCURACY_MULTIPLIER = 4.0
        private const val STATIONARY_JITTER_MIN_EFFECTIVE_METERS = 6.0
        private const val STATIONARY_JITTER_EFFECTIVE_ACCURACY_MULTIPLIER = 2.0
    }
}
