package com.geovault.tracker.policy.filter

import com.geovault.common.geo.GeoMath
import kotlin.math.abs
import kotlin.math.max

internal class SpeedCapRecoveryGate(
    private var config: SpeedRecoveryConfig,
) {
    private data class Candidate(
        val first: LocationInput,
        val last: LocationInput,
        val lastSpeedMps: Double,
        val lastCourseDegrees: Double?,
        val consistentFixes: Int,
        val promotableFixes: Int,
    )

    sealed interface Decision {
        data object Reject : Decision
        data object Hold : Decision
        data object Confirmed : Decision
    }

    private var candidate: Candidate? = null

    fun applyConfig(newConfig: SpeedRecoveryConfig) {
        config = newConfig
        candidate = null
    }

    fun reset() {
        candidate = null
    }

    fun evaluate(input: LocationInput, metrics: LocationMetrics): Decision {
        val speedMps = max(metrics.reportedSpeedMps, metrics.impliedSpeedMps)
        if (!isPromotable(speedMps, metrics)) {
            candidate = null
            return Decision.Reject
        }

        val existing = candidate
        val next = if (existing == null || isExpired(existing.first, input) || !isContinuous(existing, input, metrics, speedMps)) {
            Candidate(
                first = input,
                last = input,
                lastSpeedMps = speedMps,
                lastCourseDegrees = input.bearingDegrees?.toDouble(),
                consistentFixes = 1,
                promotableFixes = 1,
            )
        } else {
            val courseDegrees = observationCourseDegrees(candidate = existing, input = input)
            existing.copy(
                last = input,
                lastSpeedMps = speedMps,
                lastCourseDegrees = courseDegrees,
                consistentFixes = existing.consistentFixes + 1,
                promotableFixes = existing.promotableFixes + 1,
            )
        }
        candidate = next

        val confirmed = next.consistentFixes >= config.requiredConsistentFixes &&
            next.promotableFixes >= config.requiredPromotableFixes
        if (confirmed) {
            candidate = null
            return Decision.Confirmed
        }
        return Decision.Hold
    }

    private fun isPromotable(speedMps: Double, metrics: LocationMetrics): Boolean {
        return metrics.accuracyMeters <= config.maxAccuracyMeters &&
            metrics.dtSeconds in config.minDtSeconds..config.maxDtSeconds &&
            speedMps <= config.maxRecoverableSpeedMps &&
            metrics.rawDistanceMeters > 0.0
    }

    private fun isExpired(first: LocationInput, input: LocationInput): Boolean {
        return input.timestampMs - first.timestampMs !in 0L..config.confirmationWindowMs
    }

    private fun isContinuous(
        candidate: Candidate,
        input: LocationInput,
        metrics: LocationMetrics,
        speedMps: Double,
    ): Boolean {
        if (abs(speedMps - candidate.lastSpeedMps) > config.maxSpeedDeltaMps) return false
        val courseDegrees = observationCourseDegrees(candidate = candidate, input = input)
        val previousCourseDegrees = candidate.lastCourseDegrees
        if (courseDegrees != null && previousCourseDegrees != null) {
            val courseDelta = GeoMath.shortestBearingDeltaDegrees(previousCourseDegrees, courseDegrees)
            if (courseDelta > config.maxCourseDeltaDegrees) return false
        }
        val distanceMeters = GeoMath.haversineMeters(
            candidate.last.latitude,
            candidate.last.longitude,
            input.latitude,
            input.longitude,
        )
        val accuracyAllowance = (candidate.last.accuracyMeters?.toDouble() ?: 0.0) + metrics.accuracyMeters
        val motionAllowance = speedMps * metrics.dtSeconds * config.continuitySpeedMultiplier
        val allowance = max(config.minContinuityMeters, max(accuracyAllowance, motionAllowance))
        return distanceMeters <= allowance
    }

    private fun observationCourseDegrees(candidate: Candidate, input: LocationInput): Double? {
        return input.bearingDegrees?.toDouble() ?: courseDegrees(from = candidate.last, to = input)
    }

    private fun courseDegrees(from: LocationInput, to: LocationInput): Double? {
        val distanceMeters = GeoMath.haversineMeters(from.latitude, from.longitude, to.latitude, to.longitude)
        if (distanceMeters < config.minContinuityMeters) return null
        return GeoMath.initialBearingDegrees(from.latitude, from.longitude, to.latitude, to.longitude)
    }
}
