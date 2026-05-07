package com.geovault.tracker.policy.filter

/**
 * Outcome of a single [LocationFilter.evaluate] call.
 *
 * - [Decision.Accepted]: the raw fix is good as-is; the trail should append
 *   the input coordinates verbatim.
 * - [Decision.Adjusted]: the fix was an outlier; the filter has clipped it
 *   toward the previous anchor by [LocationFilterResult.cappedDistanceMeters].
 *   The caller should *replace* the raw geometry with the adjusted lat/lon
 *   before storing or streaming.
 * - [Decision.Rejected]: the fix is too far gone; the caller must drop it.
 */
data class LocationFilterResult(
    val decision: Decision,
    val reason: String,
    val adjustedLatitude: Double?,
    val adjustedLongitude: Double?,
    val cappedDistanceMeters: Double?,
    val metrics: LocationMetrics,
) {
    enum class Decision { Accepted, Adjusted, Rejected }

    companion object {
        fun accepted(reason: String, metrics: LocationMetrics): LocationFilterResult =
            LocationFilterResult(Decision.Accepted, reason, null, null, null, metrics)

        fun adjusted(
            reason: String,
            adjustedLatitude: Double,
            adjustedLongitude: Double,
            cappedDistanceMeters: Double,
            metrics: LocationMetrics,
        ): LocationFilterResult = LocationFilterResult(
            Decision.Adjusted, reason, adjustedLatitude, adjustedLongitude, cappedDistanceMeters, metrics
        )

        fun rejected(reason: String, metrics: LocationMetrics): LocationFilterResult =
            LocationFilterResult(Decision.Rejected, reason, null, null, null, metrics)
    }
}
