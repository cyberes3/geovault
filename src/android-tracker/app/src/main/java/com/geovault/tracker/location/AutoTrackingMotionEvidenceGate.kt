package com.geovault.tracker.location

import com.geovault.common.geo.GeoMath
import com.geovault.tracker.policy.filter.FilterReason
import com.geovault.tracker.policy.filter.LocationFilterReasonPolicy
import com.geovault.tracker.policy.TrackPointDecisionMetrics
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

data class AutoTrackingMotionEvidenceConfig(
    val minSpeedMps: Double = 3.0,
    val maxSpeedMps: Double = 60.0,
    val maxAccuracyMeters: Double = 35.0,
    val minDtSeconds: Double = 2.0,
    val maxDtSeconds: Double = 45.0,
    val maxSpeedDeltaMps: Double = 16.0,
    val maxCourseDeltaDegrees: Double = 50.0,
    val minContinuityMeters: Double = 25.0,
    val continuitySpeedMultiplier: Double = 1.5,
    // When the very first observation is already strong (tight accuracy AND
    // a speed clearly above the WALKING upper band), the continuity check is
    // not informative -- there is no prior anchor it could disagree with --
    // so we emit immediately while still storing the observation for the
    // *next* fix's continuity check. Set thresholds well above ambient
    // walking values so a phantom multipath burst cannot fast-emit.
    val fastEmitAccuracyMeters: Double = 10.0,
    val fastEmitSpeedMps: Double = 5.0,
)

data class AutoTrackingMotionEvidence(
    val speedMps: Float,
    val confidence: AutoTrackingMotionEvidenceConfidence,
    val path: EvidencePath,
)

enum class EvidencePath {
    /** First-fix strong-confidence shortcut (no continuity prior available). */
    FAST_EMIT,

    /** Standard two-fix continuity handshake. */
    HANDSHAKE,
}

/**
 * Converts rejected/held filter decisions into auto-mode evidence only when
 * the raw observations form a coherent movement sequence.
 */
class AutoTrackingMotionEvidenceGate(
    private val config: AutoTrackingMotionEvidenceConfig = AutoTrackingMotionEvidenceConfig(),
) {
    private data class Observation(
        val latitude: Double,
        val longitude: Double,
        val timestampMs: Long,
        val accuracyMeters: Double,
        val speedMps: Double,
        val courseDegrees: Double?,
    )

    private var lastObservation: Observation? = null

    fun reset() {
        lastObservation = null
    }

    fun evaluate(metrics: TrackPointDecisionMetrics, eventTimeMs: Long): AutoTrackingMotionEvidence? {
        if (!isSupportedReason(metrics.reason)) {
            reset()
            return null
        }
        val observation = observationFrom(metrics = metrics, eventTimeMs = eventTimeMs) ?: run {
            reset()
            return null
        }

        val previous = lastObservation
        if (previous == null) {
            lastObservation = observation
            if (isStrongFirstFix(observation)) {
                return AutoTrackingMotionEvidence(
                    speedMps = observation.speedMps.toFloat(),
                    confidence = AutoTrackingMotionEvidenceConfidence.High,
                    path = EvidencePath.FAST_EMIT,
                )
            }
            return null
        }
        if (!isContinuous(previous = previous, current = observation, metrics = metrics)) {
            lastObservation = observation
            return null
        }
        lastObservation = observation.copy(courseDegrees = courseDegrees(previous, observation))
        return AutoTrackingMotionEvidence(
            speedMps = observation.speedMps.toFloat(),
            confidence = AutoTrackingMotionEvidenceConfidence.High,
            path = EvidencePath.HANDSHAKE,
        )
    }

    private fun observationFrom(metrics: TrackPointDecisionMetrics, eventTimeMs: Long): Observation? {
        val accuracy = metrics.accuracyMeters?.toDouble() ?: return null
        val latitude = metrics.rawLatitude ?: return null
        val longitude = metrics.rawLongitude ?: return null
        val speedMps = max(metrics.impliedSpeedMps, speedFromEffectiveDistance(metrics))
        if (accuracy > config.maxAccuracyMeters) return null
        if (metrics.elapsedSeconds !in config.minDtSeconds..config.maxDtSeconds) return null
        if (speedMps !in config.minSpeedMps..config.maxSpeedMps) return null
        if (metrics.rawDistanceMeters <= 0.0 || metrics.effectiveDistanceMeters <= 0.0) return null
        return Observation(
            latitude = latitude,
            longitude = longitude,
            timestampMs = eventTimeMs,
            accuracyMeters = accuracy,
            speedMps = speedMps,
            courseDegrees = null,
        )
    }

    private fun speedFromEffectiveDistance(metrics: TrackPointDecisionMetrics): Double {
        if (metrics.elapsedSeconds <= 0.0) return 0.0
        return metrics.effectiveDistanceMeters / metrics.elapsedSeconds
    }

    private fun isContinuous(
        previous: Observation,
        current: Observation,
        metrics: TrackPointDecisionMetrics,
    ): Boolean {
        if (current.timestampMs - previous.timestampMs !in 0L..(config.maxDtSeconds * 1000.0).toLong()) {
            return false
        }
        if (abs(current.speedMps - previous.speedMps) > config.maxSpeedDeltaMps) return false
        val distanceMeters = GeoMath.haversineMeters(
            previous.latitude,
            previous.longitude,
            current.latitude,
            current.longitude,
        )
        val accuracyAllowance = previous.accuracyMeters + current.accuracyMeters
        val motionAllowance = current.speedMps * metrics.elapsedSeconds * config.continuitySpeedMultiplier
        val allowance = max(config.minContinuityMeters, max(accuracyAllowance, motionAllowance))
        if (distanceMeters > allowance) return false
        val previousCourse = previous.courseDegrees ?: courseDegrees(previous, current)
        val currentCourse = courseDegrees(previous, current)
        if (previousCourse != null && currentCourse != null) {
            val courseDelta = GeoMath.shortestBearingDeltaDegrees(previousCourse, currentCourse)
            if (courseDelta > config.maxCourseDeltaDegrees) return false
        }
        return true
    }

    private fun courseDegrees(from: Observation, to: Observation): Double? {
        val distanceMeters = GeoMath.haversineMeters(from.latitude, from.longitude, to.latitude, to.longitude)
        if (distanceMeters < config.minContinuityMeters) return null
        val fromLatRad = Math.toRadians(from.latitude)
        val toLatRad = Math.toRadians(to.latitude)
        val deltaLonRad = Math.toRadians(to.longitude - from.longitude)
        val y = sin(deltaLonRad) * cos(toLatRad)
        val x = cos(fromLatRad) * sin(toLatRad) - sin(fromLatRad) * cos(toLatRad) * cos(deltaLonRad)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun isSupportedReason(reason: String?): Boolean {
        return LocationFilterReasonPolicy.isCapEvidence(FilterReason.fromWire(reason))
    }

    private fun isStrongFirstFix(observation: Observation): Boolean {
        return observation.accuracyMeters <= config.fastEmitAccuracyMeters &&
            observation.speedMps >= config.fastEmitSpeedMps
    }
}
