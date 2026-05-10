package com.geovault.tracker.policy

import com.geovault.tracker.policy.filter.LocationFilter
import com.geovault.tracker.policy.filter.LocationFilterConfig
import com.geovault.tracker.policy.filter.LocationFilterResult
import com.geovault.tracker.policy.filter.LocationInput
import com.geovault.tracker.policy.filter.LocationMetrics
import com.geovault.tracker.policy.filter.StationaryConfidence
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

enum class TrackPointRejectReason {
    INVALID_COORDINATES,
    OUT_OF_ORDER,
    DUPLICATE,
    BAD_ACCURACY,
    TOO_FAR_FUTURE,
    STALE,
    JUMP,
}

data class TrackPointDecision(
    val accepted: Boolean,
    val canonicalEvent: TrackPointEvent?,
    val quality: TrackPointQuality = TrackPointQuality.HIGH_CONFIDENCE,
    val rejectReason: TrackPointRejectReason? = null,
    val adjusted: Boolean = false,
    val adjustmentReason: String? = null,
    val metrics: TrackPointDecisionMetrics? = null,
)

data class TrackPointDecisionMetrics(
    val rawDistanceMeters: Double,
    val effectiveDistanceMeters: Double,
    val elapsedSeconds: Double,
    val impliedSpeedMps: Double,
    val accuracyMeters: Float?,
    val rollingAverageStepMeters: Double,
    val capCandidateMeters: Double,
    val decision: String,
    val reason: String?,
    val stationaryConfidence: StationaryConfidence? = null,
)

/**
 * Stream-keyed thin facade over [LocationFilter]. The facade owns the
 * per-stream filter instances (one per (source, trackId)) and is
 * responsible for the four "non-filter" gates that protect every fix
 * regardless of the positioning math:
 *
 *  1. Invalid coordinates
 *  2. Future-skew (clock drift protection)
 *  3. Freshness TTL (stale fix rejection)
 *  4. Per-stream out-of-order / duplicate (vs the filter's own anchor)
 *
 * Everything else -- accuracy threshold, RSS distance, accCap, kinCap,
 * rollingCap, implied-anomaly boolean, Kalman smoothing, anchor-trust --
 * lives inside [LocationFilter].
 */
object TrackPointPolicyEngine {
    const val ADJUSTMENT_REASON_UNCERTAINTY_SUPPRESSED = "UNCERTAINTY_SUPPRESSED"
    const val ADJUSTMENT_REASON_OUTLIER_CAPPED = "OUTLIER_CAPPED"

    private val filters = ConcurrentHashMap<String, LocationFilter>()

    fun evaluate(
        event: TrackPointEvent,
        nowMs: Long,
        nowElapsedRealtimeNanos: Long? = null,
        config: LocationFilterConfig,
    ): TrackPointDecision {
        if (event.lat !in -90.0..90.0 || event.lon !in -180.0..180.0) {
            return rejectWithBaseMetrics(
                rejectReason = TrackPointRejectReason.INVALID_COORDINATES,
                accuracyMeters = event.accuracyMeters,
                reason = "invalid-coordinates",
            )
        }

        val normalizedTimestampMs = CanonicalTimeNormalizer.normalizeTimestampMs(
            timestamp = event.timestampMs,
            nowMs = nowMs,
            normalizeSeconds = config.normalizeSecondsTimestamps,
        )

        if (normalizedTimestampMs - nowMs > config.maxFutureSkewMs) {
            return rejectWithBaseMetrics(
                rejectReason = TrackPointRejectReason.TOO_FAR_FUTURE,
                accuracyMeters = event.accuracyMeters,
                reason = "too-far-future",
            )
        }

        if (config.freshnessTtlMs > 0L) {
            val ageMs = CanonicalTimeNormalizer.ageMs(
                nowMs = nowMs,
                eventMs = normalizedTimestampMs,
                nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
                eventElapsedRealtimeNanos = event.elapsedRealtimeNanos,
            )
            if (ageMs < 0L || ageMs > config.freshnessTtlMs) {
                return rejectWithBaseMetrics(
                    rejectReason = TrackPointRejectReason.STALE,
                    accuracyMeters = event.accuracyMeters,
                    reason = "stale",
                )
            }
        }

        val streamKey = streamKey(event.source, event.trackId)
        val filter = filterFor(streamKey, config)

        val previous = filter.lastAcceptedTimestampMs
        if (previous != null) {
            if (normalizedTimestampMs < previous) {
                return rejectWithBaseMetrics(
                    rejectReason = TrackPointRejectReason.OUT_OF_ORDER,
                    accuracyMeters = event.accuracyMeters,
                    reason = "out-of-order",
                )
            }
            val previousLatLon = filter.lastAcceptedLatLon
            if (previousLatLon != null &&
                normalizedTimestampMs == previous &&
                abs(event.lon - previousLatLon.second) < 1e-9 &&
                abs(event.lat - previousLatLon.first) < 1e-9
            ) {
                return rejectWithBaseMetrics(
                    rejectReason = TrackPointRejectReason.DUPLICATE,
                    accuracyMeters = event.accuracyMeters,
                    reason = "duplicate",
                )
            }
        }

        val input = LocationInput(
            latitude = event.lat,
            longitude = event.lon,
            timestampMs = normalizedTimestampMs,
            elapsedRealtimeNanos = event.elapsedRealtimeNanos,
            accuracyMeters = event.accuracyMeters,
            speedMps = event.gpsSpeedMps,
            bearingDegrees = event.gpsBearingDeg,
        )

        val result = filter.evaluate(input)
        return mapResultToDecision(
            event = event,
            normalizedTimestampMs = normalizedTimestampMs,
            result = result,
            config = config,
        )
    }

    fun resetStream(source: TrackPointSource, trackId: String) {
        filters.remove(streamKey(source, trackId))
    }

    fun resetAll() {
        filters.clear()
    }

    /**
     * Notify the per-stream filter that motion state changed (e.g. GPS
     * resumed from a paused-for-stationary window). The next fix is treated
     * as a resume boundary: real movement is accepted, while poor-accuracy
     * stationary jitter can still snap to the pre-pause anchor.
     */
    fun notifyMotionChanged(source: TrackPointSource, trackId: String) {
        filters[streamKey(source, trackId)]?.onMotionChanged()
    }

    private fun filterFor(streamKey: String, config: LocationFilterConfig): LocationFilter {
        return filters.compute(streamKey) { _, existing ->
            when {
                existing == null -> LocationFilter(config)
                existing.currentConfig != config -> existing.also { it.applyConfig(config) }
                else -> existing
            }
        }!!
    }

    private fun mapResultToDecision(
        event: TrackPointEvent,
        normalizedTimestampMs: Long,
        result: LocationFilterResult,
        config: LocationFilterConfig,
    ): TrackPointDecision {
        val translatedMetrics = result.metrics.toDecisionMetrics(
            accuracyMeters = event.accuracyMeters,
            decision = when (result.decision) {
                LocationFilterResult.Decision.Rejected -> "rejected"
                else -> "accepted"
            },
            reason = result.reason,
        )
        return when (result.decision) {
            LocationFilterResult.Decision.Rejected -> {
                val rejectReason = when (result.reason) {
                    "low-accuracy" -> TrackPointRejectReason.BAD_ACCURACY
                    else -> TrackPointRejectReason.JUMP
                }
                TrackPointDecision(
                    accepted = false,
                    canonicalEvent = null,
                    rejectReason = rejectReason,
                    metrics = translatedMetrics,
                )
            }

            LocationFilterResult.Decision.Adjusted -> {
                val adjustedLat = result.adjustedLatitude ?: event.lat
                val adjustedLon = result.adjustedLongitude ?: event.lon
                val quality = qualityFromMetrics(event.accuracyMeters, config)
                val adjustmentReason = when (result.reason) {
                    "uncertainty-suppressed" -> ADJUSTMENT_REASON_UNCERTAINTY_SUPPRESSED
                    else -> ADJUSTMENT_REASON_OUTLIER_CAPPED
                }
                TrackPointDecision(
                    accepted = true,
                    canonicalEvent = event.copy(
                        lat = adjustedLat,
                        lon = adjustedLon,
                        timestampMs = normalizedTimestampMs,
                        quality = quality,
                    ),
                    quality = quality,
                    adjusted = true,
                    adjustmentReason = adjustmentReason,
                    metrics = translatedMetrics,
                )
            }

            LocationFilterResult.Decision.Accepted -> {
                val quality = qualityFromMetrics(event.accuracyMeters, config)
                val canonical = event.copy(
                    timestampMs = normalizedTimestampMs,
                    quality = quality,
                )
                TrackPointDecision(
                    accepted = true,
                    canonicalEvent = canonical,
                    quality = quality,
                    metrics = translatedMetrics,
                )
            }
        }
    }

    private fun qualityFromMetrics(accuracy: Float?, config: LocationFilterConfig): TrackPointQuality {
        if (accuracy == null) return TrackPointQuality.DEGRADED
        val threshold = config.trackingAccuracyThresholdMeters
        return if (accuracy.toDouble() > threshold) TrackPointQuality.DEGRADED else TrackPointQuality.HIGH_CONFIDENCE
    }

    private fun rejectWithBaseMetrics(
        rejectReason: TrackPointRejectReason,
        accuracyMeters: Float?,
        reason: String,
    ): TrackPointDecision {
        return TrackPointDecision(
            accepted = false,
            canonicalEvent = null,
            rejectReason = rejectReason,
            metrics = TrackPointDecisionMetrics(
                rawDistanceMeters = 0.0,
                effectiveDistanceMeters = 0.0,
                elapsedSeconds = 0.0,
                impliedSpeedMps = 0.0,
                accuracyMeters = accuracyMeters,
                rollingAverageStepMeters = 0.0,
                capCandidateMeters = 0.0,
                decision = "rejected",
                reason = reason,
            ),
        )
    }

    private fun streamKey(source: TrackPointSource, trackId: String): String = "${source.name}:${trackId.trim()}"

    private fun LocationMetrics.toDecisionMetrics(
        accuracyMeters: Float?,
        decision: String,
        reason: String?,
    ): TrackPointDecisionMetrics = TrackPointDecisionMetrics(
        rawDistanceMeters = rawDistanceMeters,
        effectiveDistanceMeters = effectiveDistanceMeters,
        elapsedSeconds = dtSeconds,
        impliedSpeedMps = impliedSpeedMps,
        accuracyMeters = accuracyMeters,
        rollingAverageStepMeters = rollingAverageStepMeters,
        capCandidateMeters = capCandidate,
        decision = decision,
        reason = reason,
        stationaryConfidence = stationary,
    )
}
