package com.geovault.tracker.policy.filter

import kotlin.math.max
import kotlin.math.min

/**
 * Adaptive 1D Kalman filter operating on the *distance from the previous
 * accepted anchor*. Used to smooth a single noisy observation channel
 * before the policy switch decides whether to accept, clip, or reject the
 * fix.
 *
 * Adaptive logic:
 *  - Per-measurement, R is set to max(reportedAccuracy^2, [tuning.r]). When
 *    the chipset reports no accuracy, R falls back to [tuning.r].
 *  - After the update step, R is gently nudged by the Normalized Innovation
 *    Squared (NIS = innovation^2 / S). NIS > 4 means we underestimated
 *    measurement noise -- bump R up. NIS < 0.5 means we overestimated --
 *    relax R toward the prior. The nudge is bounded so a single bad fix
 *    cannot blow up the filter.
 *  - Q is scaled by a smooth speed-band multiplier so high-speed segments
 *    propagate slightly more state uncertainty (allowing legitimate motion
 *    through) while standstill segments tighten Q (rejecting jitter).
 *  - [reset] is called by [LocationFilter] on motion-mode change or when
 *    the running anchor is invalidated.
 *
 * This is intentionally a textbook discrete 1D Kalman: state x is a scalar
 * "displacement from anchor" and the observation z is the current raw
 * displacement. Higher-dimensional formulations are unnecessary here
 * because [LocationMetricsEngine] already provides the bearing + heading
 * stability signals separately.
 */
class KalmanFilter(
    private val tuning: KalmanTuning = KalmanTuning.forProfile(KalmanProfile.Default),
) {
    private var x: Double = tuning.x0
    private var p: Double = tuning.p0
    private var q: Double = tuning.q
    private var r: Double = tuning.r
    private var initialised: Boolean = false

    val state: Double get() = x
    val covariance: Double get() = p
    val processNoise: Double get() = q
    val measurementNoise: Double get() = r

    fun reset() {
        x = tuning.x0
        p = tuning.p0
        q = tuning.q
        r = tuning.r
        initialised = false
    }

    /**
     * Tune Q to the current motion band. [speedMps] should be the most
     * recently observed reported speed (or the implied speed if reported
     * is unavailable).
     */
    fun configureForSpeed(speedMps: Double) {
        val v = speedMps.coerceAtLeast(0.0)
        val multiplier = when {
            v < 0.5 -> 0.6
            v < 2.0 -> 0.85
            v < 8.0 -> 1.0
            v < 20.0 -> 1.4
            else -> 1.8
        }
        q = tuning.q * multiplier
    }

    /**
     * Run the predict + update cycle for one measurement.
     *
     * @param measurement raw displacement-from-anchor for the new fix
     * @param accuracyMeters chipset-reported accuracy; null falls back to
     *   the tuning prior
     * @return the smoothed displacement that callers should treat as the
     *   filtered "distance" between the anchor and the new fix
     */
    fun update(measurement: Double, accuracyMeters: Double?): Double {
        if (!initialised) {
            x = measurement
            initialised = true
        }

        p += q

        val rMeasurement = if (accuracyMeters != null && accuracyMeters > 0.0) {
            max(accuracyMeters * accuracyMeters, tuning.r)
        } else {
            r
        }

        val s = p + rMeasurement
        val kGain = if (s > 0.0) p / s else 0.0
        val innovation = measurement - x
        x += kGain * innovation
        p = (1.0 - kGain) * p

        val nis = if (s > 0.0) innovation * innovation / s else 0.0
        r = adaptMeasurementNoise(currentR = rMeasurement, nis = nis)

        return x
    }

    private fun adaptMeasurementNoise(currentR: Double, nis: Double): Double {
        val target = when {
            nis > 4.0 -> currentR * 1.25
            nis < 0.5 -> currentR * 0.8
            else -> currentR
        }
        val floor = tuning.r * 0.5
        val ceiling = tuning.r * 8.0
        return min(max(target, floor), ceiling)
    }
}
