package com.geovault.tracker.policy.filter

/**
 * Tuning preset for the adaptive 1D Kalman filter.
 *
 * - [Default] balances responsiveness and smoothing for everyday tracking.
 * - [Aggressive] is more responsive (lower process noise, lower measurement
 *   noise prior) -- catches direction changes quickly but lets more
 *   measurement jitter through.
 * - [Conservative] is more smoothed (higher process noise tolerance, higher
 *   measurement noise prior) -- snaps less, but lags briefly behind sudden
 *   real motion.
 * - [Custom] is a placeholder for caller-supplied tuning.
 */
enum class KalmanProfile {
    Default,
    Aggressive,
    Conservative,
    Custom;

    companion object {
        fun fromIdOrDefault(id: Int): KalmanProfile = entries.firstOrNull { it.ordinal == id } ?: Default
    }
}
