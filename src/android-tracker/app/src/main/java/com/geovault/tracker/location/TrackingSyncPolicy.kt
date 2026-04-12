package com.geovault.tracker.location

enum class SyncFailureClass {
    NONE,
    TRANSIENT,
    PERMANENT
}

object TrackingSyncPolicy {
    private const val BASE_DELAY_MS = 60_000L
    private const val MAX_TRANSIENT_DELAY_MS = 15L * 60L * 1000L
    private const val PERMANENT_DELAY_MS = 30L * 60L * 1000L

    fun nextRetryDelayMs(
        consecutiveFailures: Int,
        failureClass: SyncFailureClass
    ): Long {
        return when (failureClass) {
            SyncFailureClass.NONE -> BASE_DELAY_MS
            SyncFailureClass.TRANSIENT -> {
                val clampedFailures = consecutiveFailures.coerceIn(1, 8)
                val multiplier = 1L shl (clampedFailures - 1)
                (BASE_DELAY_MS * multiplier).coerceAtMost(MAX_TRANSIENT_DELAY_MS)
            }
            SyncFailureClass.PERMANENT -> PERMANENT_DELAY_MS
        }
    }
}
