package com.geovault.tracker.sensor

/**
 * Coarse motion classification derived from inertial sensors, independent of GPS.
 *
 * Used as a hint to other subsystems (e.g. stationary detection, GPS sampling rate)
 * but never as a direct command to override GPS-derived motion modes.
 */
enum class ImuClassification {
    /** Device is stationary; supports GPS pause acceleration via stationary confidence. */
    STATIONARY,
    /** Foot-based motion confirmed by step detector. */
    PEDESTRIAN,
    /** Non-pedestrian motion detected: vehicle, bicycle, or any other transport. */
    VEHICULAR,
    /** Classification unavailable: insufficient samples, sensors unavailable, or conflicting signals. */
    UNKNOWN,
}
