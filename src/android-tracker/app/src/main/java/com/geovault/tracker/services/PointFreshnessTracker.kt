package com.geovault.tracker.services

import com.geovault.tracker.location.PositioningRecoveryConfig

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
        // An internally-accepted fix (e.g. UNCERTAINTY_SUPPRESSED while stationary) confirms
        // GPS is active and the device position is known. Allow GPS pause based on either
        // timestamp so a stationary device receiving only snap fixes is not held running by
        // STALE_LOCAL_POINT when no new trail point is needed.
        val referenceMs = maxOf(lastLocalPointPersistedAtMs, lastInternalAcceptedAtMs)
        if (referenceMs == 0L) return false
        return nowMs - referenceMs <= maxAllowedPointGapMs(intervalSec)
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
        private val MAX_LOCAL_FRESHNESS_GAP_MS = PositioningRecoveryConfig.DEFAULT_MAX_LOCAL_POINT_GAP_MS
        private const val INTERVAL_MULTIPLIER = 3L

        fun maxAllowedPointGapMsForInterval(intervalSec: Long): Long {
            val intervalMs = intervalSec.coerceAtLeast(1L) * 1_000L
            return (intervalMs * INTERVAL_MULTIPLIER)
                .coerceAtLeast(MIN_LOCAL_FRESHNESS_GAP_MS)
                .coerceAtMost(MAX_LOCAL_FRESHNESS_GAP_MS)
        }
    }
}
