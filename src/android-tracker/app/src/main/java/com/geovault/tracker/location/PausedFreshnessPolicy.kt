package com.geovault.tracker.location

import android.location.Location
import android.os.Bundle

enum class PausedFreshnessDecisionReason(val telemetryValue: String) {
    EMIT("emit"),
    NO_ANCHOR("no_anchor"),
    POOR_ACCURACY("poor_accuracy"),
    MOVED("moved"),
    TOO_SOON("too_soon"),
}

data class PausedFreshnessDecision(
    val shouldEmit: Boolean,
    val reason: PausedFreshnessDecisionReason,
    val distanceMeters: Float?,
    val accuracyMeters: Float?,
    val elapsedSinceLastFreshnessMs: Long?,
) {
    companion object {
        fun emit(
            distanceMeters: Float,
            accuracyMeters: Float?,
            elapsedSinceLastFreshnessMs: Long?,
        ): PausedFreshnessDecision {
            return PausedFreshnessDecision(
                shouldEmit = true,
                reason = PausedFreshnessDecisionReason.EMIT,
                distanceMeters = distanceMeters,
                accuracyMeters = accuracyMeters,
                elapsedSinceLastFreshnessMs = elapsedSinceLastFreshnessMs,
            )
        }

        fun skip(
            reason: PausedFreshnessDecisionReason,
            distanceMeters: Float?,
            accuracyMeters: Float?,
            elapsedSinceLastFreshnessMs: Long?,
        ): PausedFreshnessDecision {
            require(reason != PausedFreshnessDecisionReason.EMIT) { "Use emit() for EMIT decisions" }
            return PausedFreshnessDecision(
                shouldEmit = false,
                reason = reason,
                distanceMeters = distanceMeters,
                accuracyMeters = accuracyMeters,
                elapsedSinceLastFreshnessMs = elapsedSinceLastFreshnessMs,
            )
        }
    }
}

/**
 * GPS-only decision policy for 5-minute freshness points emitted while the
 * tracker is paused for stationarity.
 *
 * This deliberately ignores accelerometer / significant-motion state. The
 * paused-state alarm wakes GPS, and this policy asks one narrow question:
 * does the fresh GPS fix still overlap the last accepted anchor closely
 * enough to refresh that anchor's timestamp without adding distance or
 * drawing a spike?
 */
object PausedFreshnessPolicy {
    fun evaluate(
        anchorLocation: Location?,
        candidateLocation: Location,
        stationaryRadiusMeters: Float,
        accuracyCeilingMeters: Float,
        freshnessIntervalMs: Long,
        nowMs: Long,
        lastFreshnessPointAtMs: Long,
    ): PausedFreshnessDecision {
        val accuracy = if (candidateLocation.hasAccuracy()) candidateLocation.accuracy else null
        val elapsedSinceLast = if (lastFreshnessPointAtMs > 0L) {
            nowMs - lastFreshnessPointAtMs
        } else {
            null
        }
        if (elapsedSinceLast != null && elapsedSinceLast < freshnessIntervalMs) {
            return PausedFreshnessDecision.skip(
                reason = PausedFreshnessDecisionReason.TOO_SOON,
                distanceMeters = null,
                accuracyMeters = accuracy,
                elapsedSinceLastFreshnessMs = elapsedSinceLast,
            )
        }

        val anchor = anchorLocation ?: return PausedFreshnessDecision.skip(
            reason = PausedFreshnessDecisionReason.NO_ANCHOR,
            distanceMeters = null,
            accuracyMeters = accuracy,
            elapsedSinceLastFreshnessMs = elapsedSinceLast,
        )

        val distance = anchor.distanceTo(candidateLocation).coerceAtLeast(0f)
        if (accuracy == null || accuracy > accuracyCeilingMeters) {
            return PausedFreshnessDecision.skip(
                reason = PausedFreshnessDecisionReason.POOR_ACCURACY,
                distanceMeters = distance,
                accuracyMeters = accuracy,
                elapsedSinceLastFreshnessMs = elapsedSinceLast,
            )
        }

        val radius = stationaryRadiusMeters.coerceAtLeast(0f)
        if (distance > radius) {
            return PausedFreshnessDecision.skip(
                reason = PausedFreshnessDecisionReason.MOVED,
                distanceMeters = distance,
                accuracyMeters = accuracy,
                elapsedSinceLastFreshnessMs = elapsedSinceLast,
            )
        }

        return PausedFreshnessDecision.emit(
            distanceMeters = distance,
            accuracyMeters = accuracy,
            elapsedSinceLastFreshnessMs = elapsedSinceLast,
        )
    }
}

object PausedFreshnessPointFactory {
    const val EXTRAS_KEY_PAUSED_FRESHNESS = "paused_freshness"
    const val EXTRAS_KEY_SOURCE_PROVIDER = "paused_freshness_source_provider"
    const val PROPS_KEY_PAUSED_FRESHNESS = "paused_freshness"
    const val PROPS_KEY_SOURCE_PROVIDER = "freshness_source_provider"

    fun buildAnchoredFreshnessLocation(
        anchorLocation: Location,
        probeLocation: Location,
        nowMs: Long,
        nowElapsedRealtimeNanos: Long,
    ): Location {
        val sourceProvider = probeLocation.provider?.takeIf { it.isNotBlank() } ?: "fused"
        return Location(anchorLocation).apply {
            time = nowMs
            elapsedRealtimeNanos = nowElapsedRealtimeNanos
            provider = "paused_freshness:$sourceProvider"
            if (probeLocation.hasAccuracy()) accuracy = probeLocation.accuracy
            extras = Bundle().apply {
                anchorLocation.extras?.let { putAll(it) }
                putBoolean(EXTRAS_KEY_PAUSED_FRESHNESS, true)
                putString(EXTRAS_KEY_SOURCE_PROVIDER, sourceProvider)
            }
        }
    }
}
