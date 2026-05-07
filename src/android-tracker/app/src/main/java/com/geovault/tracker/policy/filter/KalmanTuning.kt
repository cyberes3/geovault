package com.geovault.tracker.policy.filter

/**
 * Static tuning constants for an adaptive 1D Kalman filter.
 *
 * @property q initial process noise covariance (variance of the system model)
 * @property r initial measurement noise covariance prior (will be replaced
 *   per-measurement by the squared GPS accuracy, but seeded from this value
 *   when the chipset reports no accuracy)
 * @property p0 initial state covariance (uncertainty about the initial estimate)
 * @property x0 initial state estimate (distance from the running anchor)
 */
data class KalmanTuning(
    val q: Double,
    val r: Double,
    val p0: Double,
    val x0: Double,
) {
    companion object {
        fun forProfile(profile: KalmanProfile): KalmanTuning = when (profile) {
            KalmanProfile.Aggressive -> KalmanTuning(q = 0.10, r = 3.5, p0 = 10.0, x0 = 0.0)
            KalmanProfile.Conservative -> KalmanTuning(q = 0.50, r = 6.0, p0 = 10.0, x0 = 0.0)
            KalmanProfile.Default, KalmanProfile.Custom -> KalmanTuning(q = 0.25, r = 4.0, p0 = 10.0, x0 = 0.0)
        }
    }
}
