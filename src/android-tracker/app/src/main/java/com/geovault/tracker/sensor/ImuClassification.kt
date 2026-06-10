package com.geovault.tracker.sensor

/**
 * Coarse motion classification derived from inertial sensors, independent of GPS.
 *
 * The classifier answers a single question — is the user on foot? — using
 * [android.hardware.Sensor.TYPE_LINEAR_ACCELERATION] and
 * [android.hardware.Sensor.TYPE_STEP_DETECTOR]. BIKING vs DRIVING distinction
 * is intentionally left to GPS speed evidence, as in the rest of the system.
 *
 * Ordinals increase with implied activity level. Floor/ceiling comparisons in
 * [com.geovault.tracker.location.AutoTrackingMotionEngine] rely on this ordering.
 */
enum class ImuClassification {
    /** Device is not moving; eligible for GPS pause acceleration via stationary confidence. */
    STATIONARY,
    /** Foot-based motion confirmed by step detector. GPS must not use BIKING or DRIVING profile. */
    PEDESTRIAN,
    /** Non-pedestrian motion detected: vehicle, bicycle, or any other transport. GPS must not use WALKING profile. */
    VEHICULAR,
    /** Classification unavailable: insufficient samples, sensors unavailable, or conflicting signals. */
    UNKNOWN,
}
