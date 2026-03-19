package com.geovault.tracker.pipeline

object CanonicalTimeNormalizer {
    private const val SECONDS_TO_MS_THRESHOLD = 1_000_000_000_000L

    fun normalizeTimestampMs(timestamp: Long, nowMs: Long): Long {
        if (timestamp <= 0L) return nowMs
        return if (timestamp in 1 until SECONDS_TO_MS_THRESHOLD) {
            timestamp * 1000L
        } else {
            timestamp
        }
    }
}

