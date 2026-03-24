package com.geovault.tracker.pipeline

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class TrackPointRejectReason {
    INVALID_COORDINATES,
    OUT_OF_ORDER,
    DUPLICATE,
    BAD_ACCURACY,
    TOO_FAR_FUTURE,
    STALE,
    JUMP
}

enum class TrackPointOutlierPolicy {
    OFF,
    ADJUST,
    STRICT
}

data class TrackPointPolicyConfig(
    val maxAccuracyMeters: Float?,
    val degradedAccuracyMultiplier: Float = 3f,
    val allowDegradedAccuracy: Boolean = true,
    val requireAccuracyForAcceptance: Boolean = false,
    val maxFutureSkewMs: Long = 5 * 60 * 1000L,
    val maxJumpSpeedMps: Double?,
    val maxBurstDistanceMeters: Double = 300.0,
    val burstWindowSeconds: Double = 10.0,
    val rollingWindowSize: Int = 5,
    val outlierDistanceMultiplier: Double = 1.5,
    val accuracyEnvelopePaddingMeters: Double = 6.0,
    val accuracyEnvelopeMultiplier: Double = 3.0,
    val minimumKinematicCapMeters: Double = 5.0,
    val rollingDistanceMultiplier: Double = 3.0,
    val outlierPolicy: TrackPointOutlierPolicy = TrackPointOutlierPolicy.STRICT,
    val freshnessTtlMs: Long? = null,
    val normalizeSecondsTimestamps: Boolean = true
)

data class TrackPointDecision(
    val accepted: Boolean,
    val canonicalEvent: TrackPointEvent?,
    val quality: TrackPointQuality = TrackPointQuality.HIGH_CONFIDENCE,
    val rejectReason: TrackPointRejectReason? = null
)

object TrackPointPolicyEngine {
    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val DEFAULT_ROLLING_STEP_FALLBACK_METERS = 6.0

    fun evaluate(
        event: TrackPointEvent,
        previous: TrackPointEvent?,
        history: List<TrackPointEvent> = emptyList(),
        nowMs: Long,
        nowElapsedRealtimeNanos: Long? = null,
        rawConfig: TrackPointPolicyConfig
    ): TrackPointDecision {
        val config = TrackPointPolicyCoercion.sanitize(rawConfig)
        if (event.lat !in -90.0..90.0 || event.lon !in -180.0..180.0) {
            return TrackPointDecision(false, null, rejectReason = TrackPointRejectReason.INVALID_COORDINATES)
        }

        val normalizedTimestampMs = CanonicalTimeNormalizer.normalizeTimestampMs(
            timestamp = event.timestampMs,
            nowMs = nowMs,
            normalizeSeconds = config.normalizeSecondsTimestamps
        )
        if (normalizedTimestampMs - nowMs > config.maxFutureSkewMs) {
            return TrackPointDecision(false, null, rejectReason = TrackPointRejectReason.TOO_FAR_FUTURE)
        }
        if (config.freshnessTtlMs != null && config.freshnessTtlMs > 0L) {
            val ageMs = CanonicalTimeNormalizer.ageMs(
                nowMs = nowMs,
                eventMs = normalizedTimestampMs,
                nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
                eventElapsedRealtimeNanos = event.elapsedRealtimeNanos
            )
            if (ageMs < 0L || ageMs > config.freshnessTtlMs) {
                return TrackPointDecision(false, null, rejectReason = TrackPointRejectReason.STALE)
            }
        }

        val accuracyMeters = event.accuracyMeters
        if (config.maxAccuracyMeters != null) {
            if (accuracyMeters == null && config.requireAccuracyForAcceptance) {
                return TrackPointDecision(false, null, rejectReason = TrackPointRejectReason.BAD_ACCURACY)
            }
            if (accuracyMeters != null) {
                if (!config.allowDegradedAccuracy && accuracyMeters > config.maxAccuracyMeters) {
                    return TrackPointDecision(false, null, rejectReason = TrackPointRejectReason.BAD_ACCURACY)
                }
                if (accuracyMeters > config.maxAccuracyMeters * config.degradedAccuracyMultiplier.coerceAtLeast(1f)) {
                    return TrackPointDecision(false, null, rejectReason = TrackPointRejectReason.BAD_ACCURACY)
                }
            }
        }

        val previousTs = previous?.timestampMs
        if (previousTs != null && normalizedTimestampMs < previousTs) {
            return TrackPointDecision(false, null, rejectReason = TrackPointRejectReason.OUT_OF_ORDER)
        }
        if (previous != null &&
            normalizedTimestampMs == previous.timestampMs &&
            abs(event.lon - previous.lon) < 1e-9 &&
            abs(event.lat - previous.lat) < 1e-9
        ) {
            return TrackPointDecision(false, null, rejectReason = TrackPointRejectReason.DUPLICATE)
        }

        if (previous != null) {
            val distanceMeters = haversineMeters(
                lat1 = previous.lat,
                lon1 = previous.lon,
                lat2 = event.lat,
                lon2 = event.lon
            )
            val dtSeconds = CanonicalTimeNormalizer.deltaSeconds(
                previousTimestampMs = previous.timestampMs,
                currentTimestampMs = normalizedTimestampMs,
                previousElapsedRealtimeNanos = previous.elapsedRealtimeNanos,
                currentElapsedRealtimeNanos = event.elapsedRealtimeNanos
            )
            val impliedSpeedMps = when {
                dtSeconds > 0.0 -> distanceMeters / dtSeconds
                distanceMeters > 0.0 -> Double.POSITIVE_INFINITY
                else -> 0.0
            }
            val previousAccuracy = previous.accuracyMeters?.toDouble()?.coerceAtLeast(0.0) ?: 0.0
            val currentAccuracy = accuracyMeters?.toDouble()?.coerceAtLeast(0.0) ?: 0.0
            val accuracyEnvelopeMeters =
                ((previousAccuracy + currentAccuracy) * config.accuracyEnvelopeMultiplier) +
                    config.accuracyEnvelopePaddingMeters
            val rollingAverageStepMeters = averageStepDistanceMeters(
                history = history,
                fallbackDistanceMeters = DEFAULT_ROLLING_STEP_FALLBACK_METERS,
                windowSize = config.rollingWindowSize
            )
            val kinematicCapMeters = maxOf(
                config.minimumKinematicCapMeters,
                accuracyEnvelopeMeters,
                rollingAverageStepMeters * config.rollingDistanceMultiplier
            )
            val compositeCapMeters = kinematicCapMeters
            val speedSpike = config.maxJumpSpeedMps != null && impliedSpeedMps > config.maxJumpSpeedMps
            val burstSpike = distanceMeters > config.maxBurstDistanceMeters &&
                dtSeconds <= config.burstWindowSeconds.coerceAtLeast(0.2)
            val capSpike = distanceMeters > (compositeCapMeters * config.outlierDistanceMultiplier.coerceAtLeast(1.0))
            val isOutlier = speedSpike || burstSpike || capSpike
            if (isOutlier && config.outlierPolicy != TrackPointOutlierPolicy.OFF) {
                return TrackPointDecision(false, null, rejectReason = TrackPointRejectReason.JUMP)
            }
        }

        val quality = when {
            accuracyMeters == null -> TrackPointQuality.DEGRADED
            config.maxAccuracyMeters != null && accuracyMeters > config.maxAccuracyMeters ->
                TrackPointQuality.DEGRADED
            else -> TrackPointQuality.HIGH_CONFIDENCE
        }
        val canonical = event.copy(
            timestampMs = normalizedTimestampMs,
            quality = quality
        )
        return TrackPointDecision(
            accepted = true,
            canonicalEvent = canonical,
            quality = quality
        )
    }

    private fun averageStepDistanceMeters(
        history: List<TrackPointEvent>,
        fallbackDistanceMeters: Double,
        windowSize: Int
    ): Double {
        if (history.size < 2) return fallbackDistanceMeters.coerceAtLeast(0.0)
        val sampleCount = windowSize.coerceIn(3, 20)
        val startIndex = (history.size - sampleCount).coerceAtLeast(0)
        var total = 0.0
        var count = 0
        for (i in (startIndex + 1) until history.size) {
            val previous = history[i - 1]
            val current = history[i]
            total += haversineMeters(
                lat1 = previous.lat,
                lon1 = previous.lon,
                lat2 = current.lat,
                lon2 = current.lon
            )
            count++
        }
        return if (count > 0) total / count else fallbackDistanceMeters.coerceAtLeast(0.0)
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val latRad1 = Math.toRadians(lat1)
        val lonRad1 = Math.toRadians(lon1)
        val latRad2 = Math.toRadians(lat2)
        val lonRad2 = Math.toRadians(lon2)
        val dLat = latRad2 - latRad1
        val dLon = lonRad2 - lonRad1
        val sinHalfLat = sin(dLat / 2.0)
        val sinHalfLon = sin(dLon / 2.0)
        val a = sinHalfLat.pow(2.0) + cos(latRad1) * cos(latRad2) * sinHalfLon.pow(2.0)
        val boundedA = a.coerceIn(0.0, 1.0)
        val c = 2.0 * asin(sqrt(boundedA))
        return EARTH_RADIUS_M * c
    }
}

