package com.geovault.tracker.pipeline

object CanonicalTimeNormalizer {
    private const val SECONDS_TO_MS_THRESHOLD = 1_000_000_000_000L

    fun normalizeTimestampMs(timestamp: Long, nowMs: Long, normalizeSeconds: Boolean = true): Long {
        if (timestamp <= 0L) return nowMs
        if (!normalizeSeconds) return timestamp
        return if (timestamp in 1 until SECONDS_TO_MS_THRESHOLD) {
            timestamp * 1000L
        } else {
            timestamp
        }
    }

    fun ageMs(
        nowMs: Long,
        eventMs: Long,
        nowElapsedRealtimeNanos: Long? = null,
        eventElapsedRealtimeNanos: Long? = null
    ): Long {
        val nowElapsed = nowElapsedRealtimeNanos ?: 0L
        val eventElapsed = eventElapsedRealtimeNanos ?: 0L
        if (nowElapsed > 0L && eventElapsed > 0L) {
            return (nowElapsed - eventElapsed) / 1_000_000L
        }
        return nowMs - eventMs
    }

    fun deltaSeconds(
        previousTimestampMs: Long,
        currentTimestampMs: Long,
        previousElapsedRealtimeNanos: Long? = null,
        currentElapsedRealtimeNanos: Long? = null
    ): Double {
        val previousElapsed = previousElapsedRealtimeNanos ?: 0L
        val currentElapsed = currentElapsedRealtimeNanos ?: 0L
        if (previousElapsed > 0L && currentElapsed > 0L && currentElapsed >= previousElapsed) {
            return (currentElapsed - previousElapsed) / 1_000_000_000.0
        }
        return ((currentTimestampMs - previousTimestampMs) / 1000.0).coerceAtLeast(0.0)
    }
}

