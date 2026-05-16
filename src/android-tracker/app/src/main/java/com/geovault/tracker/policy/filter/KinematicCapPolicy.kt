package com.geovault.tracker.policy.filter

import kotlin.math.max
import kotlin.math.min

/**
 * Converts noisy chipset speed evidence into the speed term used by the
 * kinematic cap. This keeps the cap calculation explicit and testable: a
 * single fused-provider speed spike should not grant walking-mode fixes
 * enough room to rubber-band hundreds of meters.
 */
class KinematicCapPolicy(
    private val config: KinematicCapConfig,
) {
    data class Decision(
        val trustedSpeedMps: Double,
        val reason: String,
    )

    fun resolve(
        reportedSpeedMps: Double,
        impliedSpeedMps: Double,
        maxAccuracyMeters: Double,
        dtSeconds: Double,
        speedStability: Double,
        bearingStability: Double,
    ): Decision {
        val safeReported = reportedSpeedMps.coerceAtLeast(0.0)
        val stableMotion = maxAccuracyMeters <= config.stableMotionAccuracyMeters &&
            speedStability >= STABLE_SIGNAL_THRESHOLD &&
            bearingStability >= STABLE_SIGNAL_THRESHOLD
        val reportedLimit = if (stableMotion) {
            config.trustedReportedSpeedLimitMps
        } else {
            config.unconfirmedReportedSpeedLimitMps
        }
        val reported = min(safeReported, reportedLimit)
        val implied = trustedImpliedSpeed(
            impliedSpeedMps = impliedSpeedMps,
            maxAccuracyMeters = maxAccuracyMeters,
            dtSeconds = dtSeconds,
            stableMotion = stableMotion,
        )
        val trusted = max(reported, implied)
        val reason = when {
            safeReported > reported && !stableMotion -> "reported-speed-clamped-unconfirmed"
            safeReported > reported -> "reported-speed-clamped-profile"
            implied > reported -> "implied-speed-trusted"
            stableMotion -> "reported-speed-stable"
            else -> "reported-speed"
        }
        return Decision(trustedSpeedMps = trusted, reason = reason)
    }

    private fun trustedImpliedSpeed(
        impliedSpeedMps: Double,
        maxAccuracyMeters: Double,
        dtSeconds: Double,
        stableMotion: Boolean,
    ): Double {
        if (dtSeconds < IMPLIED_SPEED_MIN_DT_SECONDS) return 0.0
        if (impliedSpeedMps < IMPLIED_SPEED_MIN_MPS) return 0.0
        if (maxAccuracyMeters > IMPLIED_SPEED_MAX_ACCURACY_METERS && !stableMotion) return 0.0
        val limit = if (stableMotion) {
            config.stableMotionSpeedLimitMps
        } else {
            config.unconfirmedReportedSpeedLimitMps
        }
        return min(impliedSpeedMps, limit)
    }

    companion object {
        private const val STABLE_SIGNAL_THRESHOLD = 0.65
        private const val IMPLIED_SPEED_MAX_ACCURACY_METERS = 15.0
        private const val IMPLIED_SPEED_MIN_DT_SECONDS = 1.0
        private const val IMPLIED_SPEED_MIN_MPS = 1.5
    }
}
