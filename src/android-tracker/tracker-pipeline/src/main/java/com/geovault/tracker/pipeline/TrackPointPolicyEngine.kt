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

data class TrackPointPolicyConfig(
    val maxAccuracyMeters: Float?,
    val degradedAccuracyMultiplier: Float = 3f,
    val maxFutureSkewMs: Long = 5 * 60 * 1000L,
    val maxJumpSpeedMps: Double?,
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
    private const val MIN_DT_FOR_SPEED_SECONDS = 0.5

    fun evaluate(
        event: TrackPointEvent,
        previous: TrackPointEvent?,
        nowMs: Long,
        config: TrackPointPolicyConfig
    ): TrackPointDecision {
        if (event.lat !in -90.0..90.0 || event.lon !in -180.0..180.0) {
            return TrackPointDecision(false, null, rejectReason = TrackPointRejectReason.INVALID_COORDINATES)
        }

        val normalizedTimestampMs = if (config.normalizeSecondsTimestamps) {
            CanonicalTimeNormalizer.normalizeTimestampMs(event.timestampMs, nowMs)
        } else if (event.timestampMs <= 0L) {
            nowMs
        } else {
            event.timestampMs
        }
        if (normalizedTimestampMs - nowMs > config.maxFutureSkewMs) {
            return TrackPointDecision(false, null, rejectReason = TrackPointRejectReason.TOO_FAR_FUTURE)
        }
        if (config.freshnessTtlMs != null && config.freshnessTtlMs > 0L) {
            val ageMs = nowMs - normalizedTimestampMs
            if (ageMs < 0L || ageMs > config.freshnessTtlMs) {
                return TrackPointDecision(false, null, rejectReason = TrackPointRejectReason.STALE)
            }
        }

        val accuracyMeters = event.accuracyMeters
        if (config.maxAccuracyMeters != null &&
            accuracyMeters != null &&
            accuracyMeters > config.maxAccuracyMeters * config.degradedAccuracyMultiplier.coerceAtLeast(1f)
        ) {
            return TrackPointDecision(false, null, rejectReason = TrackPointRejectReason.BAD_ACCURACY)
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

        if (previous != null && config.maxJumpSpeedMps != null) {
            val dtSeconds = (normalizedTimestampMs - previous.timestampMs) / 1000.0
            if (dtSeconds >= MIN_DT_FOR_SPEED_SECONDS) {
                val distanceMeters = haversineMeters(
                    lat1 = previous.lat,
                    lon1 = previous.lon,
                    lat2 = event.lat,
                    lon2 = event.lon
                )
                val speedMps = distanceMeters / dtSeconds
                if (speedMps > config.maxJumpSpeedMps) {
                    return TrackPointDecision(false, null, rejectReason = TrackPointRejectReason.JUMP)
                }
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

