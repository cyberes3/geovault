package com.geovault.tracker.sensor

/**
 * Snapshot of a single stable IMU classification event emitted by [ImuMotionClassifier].
 *
 * Only emitted after the classification has been stable for at least 15 s, and then
 * re-emitted every 15 s as a heartbeat. All numeric values reflect measurements taken
 * over their respective rolling windows at the moment of emission.
 */
data class ImuMotionContext(
    val classification: ImuClassification,
    /** Classification confidence in [0.0, 1.0]. */
    val confidence: Float,
    /** Variance of linear acceleration magnitude over the 10 s sampling window, in m²/s⁴. */
    val accelerationVarianceMps4: Float,
    /** Detected step rate over the 30 s rolling window, in steps/min. */
    val stepRatePerMinute: Float,
)
