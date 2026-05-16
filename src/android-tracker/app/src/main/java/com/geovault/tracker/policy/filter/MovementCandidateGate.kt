package com.geovault.tracker.policy.filter

import com.geovault.common.geo.GeoMath

/**
 * Confirmation gate for suspicious movement candidates.
 *
 * The gate is deliberately small: it only owns candidate lifecycle. The
 * filter still owns whether a fix is generally accepted, clipped, held, or
 * rejected.
 */
class MovementCandidateGate(
    private var config: MovementCandidateConfig,
) {
    private data class Candidate(
        val first: LocationInput,
        val consistentFixes: Int,
        val promotableFixes: Int,
    )

    enum class Decision { Allow, Hold }

    private var candidate: Candidate? = null

    fun applyConfig(newConfig: MovementCandidateConfig) {
        config = newConfig
        candidate = null
    }

    fun reset() {
        candidate = null
    }

    fun assess(
        input: LocationInput,
        previousAnchor: LocationInput,
        metrics: LocationMetrics,
        anchorSuspect: Boolean,
    ): Decision {
        if (!config.enabled) {
            candidate = null
            return Decision.Allow
        }
        val anchorDistance = GeoMath.haversineMeters(
            previousAnchor.latitude,
            previousAnchor.longitude,
            input.latitude,
            input.longitude,
        )
        val materiallyAwayFromAnchor = anchorDistance >= minOf(
            config.suspectDistanceMeters,
            config.consistencyMeters,
        )
        val suspicious = materiallyAwayFromAnchor &&
            (anchorSuspect ||
                metrics.accuracyMeters >= config.suspectAccuracyMeters ||
                metrics.impliedSpeedMps >= config.suspectImpliedSpeedMps)
        if (!suspicious) {
            candidate = null
            return Decision.Allow
        }

        val existing = candidate
        val next = if (existing == null || !isConsistent(existing.first, input) || isExpired(existing.first, input)) {
            Candidate(
                first = input,
                consistentFixes = 1,
                promotableFixes = promotableCount(metrics),
            )
        } else {
            existing.copy(
                consistentFixes = existing.consistentFixes + 1,
                promotableFixes = existing.promotableFixes + promotableCount(metrics),
            )
        }
        candidate = next

        val confirmed = next.consistentFixes >= config.requiredConsistentFixes &&
            next.promotableFixes >= config.requiredPromotableFixes
        if (confirmed) {
            candidate = null
            return Decision.Allow
        }
        return Decision.Hold
    }

    private fun isConsistent(first: LocationInput, next: LocationInput): Boolean {
        val distance = GeoMath.haversineMeters(first.latitude, first.longitude, next.latitude, next.longitude)
        return distance <= config.consistencyMeters
    }

    private fun isExpired(first: LocationInput, next: LocationInput): Boolean {
        return next.timestampMs - first.timestampMs > config.confirmationWindowMs
    }

    private fun promotableCount(metrics: LocationMetrics): Int =
        if (metrics.accuracyMeters <= config.promotionAccuracyMeters) 1 else 0
}
