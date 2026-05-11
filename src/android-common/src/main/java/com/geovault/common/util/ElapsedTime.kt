package com.geovault.common.util

/**
 * Bucketed representation of an elapsed wall-clock duration, suitable for "ago" style
 * relative-time UIs. Centralizing the bucketing here means every screen that renders
 * "X ago" agrees on bucket boundaries (e.g. "what counts as now", "when does 59s become 1m").
 *
 * Renderers should match on the variant and produce a localized string, e.g. `Now -> "now"`
 * or `Now -> "Just updated"` depending on the target surface.
 */
sealed interface ElapsedTime {
    data object Now : ElapsedTime
    data class Seconds(val value: Int) : ElapsedTime
    data class Minutes(val value: Int) : ElapsedTime
    data class Hours(val value: Int) : ElapsedTime
    data class Days(val value: Int) : ElapsedTime

    companion object {
        /** Elapsed durations strictly less than this collapse to [Now]. */
        const val DEFAULT_NOW_THRESHOLD_MS: Long = 10_000L

        /**
         * Bucket an elapsed duration in milliseconds. Negative inputs (e.g. clock skew)
         * are clamped to zero and treated as [Now] under the default threshold.
         */
        fun from(elapsedMs: Long, nowThresholdMs: Long = DEFAULT_NOW_THRESHOLD_MS): ElapsedTime {
            val safe = elapsedMs.coerceAtLeast(0L)
            return when {
                safe < nowThresholdMs -> Now
                safe < 60_000L -> Seconds((safe / 1_000L).toInt())
                safe < 3_600_000L -> Minutes((safe / 60_000L).toInt())
                safe < 86_400_000L -> Hours((safe / 3_600_000L).toInt())
                else -> Days((safe / 86_400_000L).toInt())
            }
        }
    }
}
