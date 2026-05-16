package com.geovault.tracker.policy.filter

/**
 * Outcome of a single [LocationFilter.evaluate] call.
 *
 * - [Decision.Commit]: the raw or adjusted fix is good enough to become
 *   the next visible/persisted track point.
 * - [Decision.Hold]: the fix may be useful internally, but must not move
 *   the visible tracker or persisted trail yet.
 * - [Decision.SnapInternal]: jitter was suppressed by preserving the
 *   current anchor internally; callers must not emit another visible point
 *   at that stale coordinate.
 * - [Decision.Reject]: the fix is too far gone; the caller must drop it.
 *
 * Commit notes:
 * - raw commit: [adjustedLatitude] / [adjustedLongitude] are null.
 * - clipped commit: adjusted lat/lon are populated and should replace
 *   raw geometry before storing or streaming.
 * - snap-internal: adjusted lat/lon identify the preserved anchor but are
 *   diagnostics only.
 *
 */
data class LocationFilterResult(
    val decision: Decision,
    val reason: String,
    val adjustedLatitude: Double?,
    val adjustedLongitude: Double?,
    val cappedDistanceMeters: Double?,
    val metrics: LocationMetrics,
) {
    enum class Decision { Commit, Hold, SnapInternal, Reject }

    companion object {
        fun commit(reason: String, metrics: LocationMetrics): LocationFilterResult =
            LocationFilterResult(Decision.Commit, reason, null, null, null, metrics)

        fun commitAdjusted(
            reason: String,
            adjustedLatitude: Double,
            adjustedLongitude: Double,
            cappedDistanceMeters: Double,
            metrics: LocationMetrics,
        ): LocationFilterResult =
            LocationFilterResult(Decision.Commit, reason, adjustedLatitude, adjustedLongitude, cappedDistanceMeters, metrics)

        fun hold(reason: String, metrics: LocationMetrics): LocationFilterResult =
            LocationFilterResult(Decision.Hold, reason, null, null, null, metrics)

        fun snapInternal(
            reason: String,
            adjustedLatitude: Double,
            adjustedLongitude: Double,
            metrics: LocationMetrics,
        ): LocationFilterResult =
            LocationFilterResult(Decision.SnapInternal, reason, adjustedLatitude, adjustedLongitude, 0.0, metrics)

        fun reject(reason: String, metrics: LocationMetrics): LocationFilterResult =
            LocationFilterResult(Decision.Reject, reason, null, null, null, metrics)
    }
}
