package com.geovault.tracker.services

/**
 * Tracks point freshness as a recording concern, independent of upload state.
 *
 * `lastLocalPointPersistedAtMs` answers whether the device has captured a
 * durable local point. `lastUploadSucceededAtMs` answers a separate question:
 * whether the server has recently accepted queued points.
 *
 * Filter snap/adjust outcomes must not implicitly define when GPS may sleep
 * or when the user-visible trail is stale.
 */
class PointFreshnessTracker {
    private var sessionStartedAtMs: Long = 0L
    var lastLocalPointPersistedAtMs: Long = 0L
        private set
    var lastInternalAcceptedAtMs: Long = 0L
        private set
    var lastUploadSucceededAtMs: Long = 0L
        private set

    fun reset(sessionStartedAtMs: Long) {
        this.sessionStartedAtMs = sessionStartedAtMs
        lastLocalPointPersistedAtMs = 0L
        lastInternalAcceptedAtMs = 0L
        lastUploadSucceededAtMs = 0L
    }

    fun markLocalPointPersisted(nowMs: Long) {
        lastLocalPointPersistedAtMs = nowMs
        lastInternalAcceptedAtMs = nowMs
    }

    /** Restores local freshness after process restart from the latest queued point. */
    fun seedLocalPointPersistedAt(persistedAtMs: Long) {
        if (persistedAtMs > lastLocalPointPersistedAtMs) {
            lastLocalPointPersistedAtMs = persistedAtMs
        }
    }

    fun markInternalAccepted(nowMs: Long) {
        lastInternalAcceptedAtMs = nowMs
    }

    fun markUploadSucceeded(nowMs: Long) {
        lastUploadSucceededAtMs = nowMs
    }

    fun localPointAgeMs(nowMs: Long): Long? {
        return lastLocalPointPersistedAtMs
            .takeIf { it > 0L }
            ?.let { nowMs - it }
    }

    fun uploadAgeMs(nowMs: Long): Long? {
        return lastUploadSucceededAtMs
            .takeIf { it > 0L }
            ?.let { nowMs - it }
    }

    fun isLocalFresh(nowMs: Long, intervalSec: Long): Boolean {
        return localPointAgeMs(nowMs)?.let { it <= maxAllowedPointGapMs(intervalSec) } == true
    }

    fun shouldForceLocalRecovery(nowMs: Long, intervalSec: Long): Boolean {
        val deadlineMs = maxAllowedPointGapMs(intervalSec)
        val localAge = localPointAgeMs(nowMs)
        if (localAge != null) return localAge > deadlineMs
        return sessionStartedAtMs > 0L && nowMs - sessionStartedAtMs > deadlineMs
    }

    fun maxAllowedPointGapMs(intervalSec: Long): Long {
        return maxAllowedPointGapMsForInterval(intervalSec)
    }

    companion object {
        private const val MIN_LOCAL_FRESHNESS_GAP_MS = 60_000L
        private const val MAX_LOCAL_FRESHNESS_GAP_MS = 90_000L
        private const val INTERVAL_MULTIPLIER = 3L

        fun maxAllowedPointGapMsForInterval(intervalSec: Long): Long {
            val intervalMs = intervalSec.coerceAtLeast(1L) * 1_000L
            return (intervalMs * INTERVAL_MULTIPLIER)
                .coerceAtLeast(MIN_LOCAL_FRESHNESS_GAP_MS)
                .coerceAtMost(MAX_LOCAL_FRESHNESS_GAP_MS)
        }
    }
}
