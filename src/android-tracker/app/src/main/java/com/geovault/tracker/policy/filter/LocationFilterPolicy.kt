package com.geovault.tracker.policy.filter

/**
 * Outlier handling strategies for the location filter.
 *
 * - [PassThrough] never modifies a fix; only labels it. Useful for diagnostics
 *   or when downstream callers want raw geometry.
 * - [Adjust] always clips an outlier toward the previous anchor by the
 *   computed cap (`distanceFilter` if set, else `capCandidate`). The fix is
 *   never dropped.
 * - [Conservative] (default) clips when the raw delta is within a 1.5x
 *   tolerance band of the cap and rejects outright when it isn't or when an
 *   implied-speed/burst anomaly is detected. Trades a momentary trail gap
 *   for a hard guarantee that a teleporting fix never reaches the trail.
 */
enum class LocationFilterPolicy {
    PassThrough,
    Adjust,
    Conservative;

    companion object {
        fun fromIdOrDefault(id: Int): LocationFilterPolicy = entries.firstOrNull { it.ordinal == id } ?: Conservative
    }
}
