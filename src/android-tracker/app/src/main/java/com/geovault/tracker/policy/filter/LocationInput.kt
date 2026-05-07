package com.geovault.tracker.policy.filter

/**
 * Pure value object carrying every chipset signal the location filter needs
 * to score a fix. Decoupled from [android.location.Location] so the engine
 * is unit-testable on the JVM.
 *
 * @property latitude WGS-84 latitude in degrees
 * @property longitude WGS-84 longitude in degrees
 * @property timestampMs canonical event time in milliseconds since epoch
 *   (already normalized by the caller -- the filter does not rescale)
 * @property elapsedRealtimeNanos optional monotonic clock reading at fix
 *   capture; preferred over wall-clock for [dt] computation when present
 * @property accuracyMeters horizontal accuracy reported by the chipset.
 *   Pass null when the provider did not report accuracy (some test/mocked
 *   sources). The filter treats null as "very low confidence" but does not
 *   silently substitute zero.
 * @property speedMps GPS-reported ground speed; null when unavailable
 * @property bearingDegrees GPS-reported course over ground in degrees
 *   (0..360); null when stationary or unavailable
 */
data class LocationInput(
    val latitude: Double,
    val longitude: Double,
    val timestampMs: Long,
    val elapsedRealtimeNanos: Long? = null,
    val accuracyMeters: Float? = null,
    val speedMps: Float? = null,
    val bearingDegrees: Float? = null,
)
