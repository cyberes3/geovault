package com.geovault.tracker.services

import com.geovault.tracker.settings.TrackerSettings
import kotlin.math.max

/**
 * Scales GPS cadence (intervals, distance filters, stationary probes).
 * [pointRateFactor] of 0.5 means roughly half as many points as [Normal].
 */
enum class PositioningDensity(val pointRateFactor: Float) {
    Normal(1f),
    Sparse(0.5f);

    fun scaleIntervalSec(baseSec: Long): Long {
        if (pointRateFactor >= 1f) return baseSec
        return max(1L, (baseSec / pointRateFactor).toLong())
    }

    fun scaleDistanceMeters(baseMeters: Float): Float {
        if (pointRateFactor >= 1f) return baseMeters
        return baseMeters / pointRateFactor
    }

    fun scaleDurationMs(baseMs: Long): Long {
        if (pointRateFactor >= 1f) return baseMs
        return max(1L, (baseMs / pointRateFactor).toLong())
    }

    companion object {
        fun from(sparseTrackingEnabled: Boolean): PositioningDensity =
            if (sparseTrackingEnabled) Sparse else Normal

        fun from(settings: TrackerSettings): PositioningDensity =
            from(settings.sparseTracking)
    }
}
