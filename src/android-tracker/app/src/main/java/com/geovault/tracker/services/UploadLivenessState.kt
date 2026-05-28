package com.geovault.tracker.services

import com.geovault.tracker.location.SyncFailureClass

data class UploadLivenessState(
    val lastSucceededAtMs: Long = 0L,
    val lastFailedAtMs: Long = 0L,
    val lastFailureClass: SyncFailureClass = SyncFailureClass.NONE,
    val consecutiveFailures: Int = 0,
    val currentSessionQueuedCount: Int = 0,
    val backlogQueuedCount: Int = 0,
) {
    val hasUploadTrouble: Boolean
        get() = consecutiveFailures > 0 && lastFailureClass != SyncFailureClass.NONE

    fun onUploadSucceeded(nowMs: Long, visibleSentCount: Int): UploadLivenessState {
        if (visibleSentCount <= 0) return this
        return copy(
            lastSucceededAtMs = nowMs,
            lastFailureClass = SyncFailureClass.NONE,
            consecutiveFailures = 0,
        )
    }

    fun onUploadResult(
        result: QueueUploadResult,
        nowMs: Long,
        updateFailureCounters: Boolean,
    ): UploadLivenessState {
        if (result.failureClass == SyncFailureClass.SKIPPED) return this
        if (result.failureClass == SyncFailureClass.NONE) {
            return copy(
                lastSucceededAtMs = if (result.rowsDeleted > 0) nowMs else lastSucceededAtMs,
                lastFailureClass = SyncFailureClass.NONE,
                consecutiveFailures = 0,
            )
        }
        return copy(
            lastFailedAtMs = nowMs,
            lastFailureClass = result.failureClass,
            consecutiveFailures = if (updateFailureCounters) consecutiveFailures + 1 else consecutiveFailures,
        )
    }

    fun withQueueCounts(
        currentSessionQueuedCount: Int,
        backlogQueuedCount: Int,
    ): UploadLivenessState {
        return copy(
            currentSessionQueuedCount = currentSessionQueuedCount.coerceAtLeast(0),
            backlogQueuedCount = backlogQueuedCount.coerceAtLeast(0),
        )
    }

    fun uploadAgeMs(nowMs: Long): Long? {
        return lastSucceededAtMs.takeIf { it > 0L }?.let { nowMs - it }
    }
}
