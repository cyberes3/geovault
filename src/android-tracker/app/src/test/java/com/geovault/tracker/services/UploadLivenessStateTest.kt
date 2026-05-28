package com.geovault.tracker.services

import com.geovault.tracker.location.SyncFailureClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadLivenessStateTest {
    @Test
    fun successClearsFailureStateAndRecordsTimestamp() {
        val state = UploadLivenessState(
            lastFailureClass = SyncFailureClass.TRANSIENT,
            consecutiveFailures = 2,
        ).onUploadResult(
            result = QueueUploadResult(
                failureClass = SyncFailureClass.NONE,
                rowsDeleted = 3,
                visibleRowsSent = 3,
            ),
            nowMs = 10_000L,
            updateFailureCounters = true,
        )

        assertEquals(10_000L, state.lastSucceededAtMs)
        assertEquals(SyncFailureClass.NONE, state.lastFailureClass)
        assertEquals(0, state.consecutiveFailures)
        assertFalse(state.hasUploadTrouble)
    }

    @Test
    fun backlogOnlySuccessfulRowsRefreshServerDeliveryFreshness() {
        val state = UploadLivenessState(
            lastSucceededAtMs = 5_000L,
        ).onUploadResult(
            result = QueueUploadResult(
                failureClass = SyncFailureClass.NONE,
                rowsDeleted = 4,
                visibleRowsSent = 0,
            ),
            nowMs = 12_000L,
            updateFailureCounters = true,
        )

        assertEquals(12_000L, state.lastSucceededAtMs)
    }

    @Test
    fun failureTracksRetryPostureSeparatelyFromQueueCounts() {
        val state = UploadLivenessState()
            .withQueueCounts(currentSessionQueuedCount = 4, backlogQueuedCount = 7)
            .onUploadResult(
                result = QueueUploadResult(
                    failureClass = SyncFailureClass.TRANSIENT,
                    interruptedByFailure = true,
                    failureReason = QueueUploadFailureReason.HTTP_TRANSIENT,
                ),
                nowMs = 20_000L,
                updateFailureCounters = true,
            )

        assertEquals(4, state.currentSessionQueuedCount)
        assertEquals(7, state.backlogQueuedCount)
        assertEquals(SyncFailureClass.TRANSIENT, state.lastFailureClass)
        assertEquals(1, state.consecutiveFailures)
        assertTrue(state.hasUploadTrouble)
    }

    @Test
    fun skippedResultDoesNotChangeFailurePosture() {
        val state = UploadLivenessState(
            lastFailureClass = SyncFailureClass.TRANSIENT,
            consecutiveFailures = 2,
        ).onUploadResult(
            result = QueueUploadResult(
                failureClass = SyncFailureClass.SKIPPED,
                skippedReason = QueueUploadSkipReason.LOCK_BUSY,
            ),
            nowMs = 30_000L,
            updateFailureCounters = true,
        )

        assertEquals(SyncFailureClass.TRANSIENT, state.lastFailureClass)
        assertEquals(2, state.consecutiveFailures)
    }
}
