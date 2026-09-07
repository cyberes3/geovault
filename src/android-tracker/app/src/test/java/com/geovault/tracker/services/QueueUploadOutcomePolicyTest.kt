package com.geovault.tracker.services

import com.geovault.tracker.location.SyncFailureClass
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueUploadOutcomePolicyTest {
    @Test
    fun httpFailureClass_treatsRetryable4xxAsTransient() {
        assertEquals(SyncFailureClass.TRANSIENT, QueueUploadOutcomePolicy.httpFailureClass(408))
        assertEquals(SyncFailureClass.TRANSIENT, QueueUploadOutcomePolicy.httpFailureClass(429))
    }

    @Test
    fun httpFailureClass_treatsOther4xxAsPermanent() {
        assertEquals(SyncFailureClass.PERMANENT, QueueUploadOutcomePolicy.httpFailureClass(400))
        assertEquals(SyncFailureClass.TRANSIENT, QueueUploadOutcomePolicy.httpFailureClass(401))
        assertEquals(SyncFailureClass.TRANSIENT, QueueUploadOutcomePolicy.httpFailureClass(403))
    }

    @Test
    fun finalResult_partialFailureIsTransientNotSuccess() {
        val partialFailure = QueueUploadOutcomePolicy.finalResult(
            batchesAttempted = 2,
            batchesSent = 1,
            rowsDeleted = 50,
            visibleRowsSent = 10,
            interruptedByFailure = true,
            failureReason = QueueUploadFailureReason.HTTP_TRANSIENT,
        )
        val success = QueueUploadOutcomePolicy.finalResult(
            batchesAttempted = 1,
            batchesSent = 1,
            rowsDeleted = 50,
            visibleRowsSent = 10,
            interruptedByFailure = false,
        )

        assertEquals(SyncFailureClass.TRANSIENT, partialFailure.failureClass)
        assertEquals(1, partialFailure.batchesSent)
        assertEquals(50, partialFailure.rowsDeleted)
        assertEquals(10, partialFailure.visibleRowsSent)
        assertEquals(SyncFailureClass.NONE, success.failureClass)
    }

    @Test
    fun skippedResult_preservesSpecificReason() {
        val result = QueueUploadOutcomePolicy.skipped(QueueUploadSkipReason.LOCK_BUSY)

        assertEquals(SyncFailureClass.SKIPPED, result.failureClass)
        assertEquals(QueueUploadSkipReason.LOCK_BUSY, result.skippedReason)
        assertEquals(0, result.batchesAttempted)
    }

    @Test
    fun finalResult_permanentHttpFailureRemainsPermanent() {
        val result = QueueUploadOutcomePolicy.finalResult(
            batchesAttempted = 1,
            batchesSent = 0,
            rowsDeleted = 0,
            visibleRowsSent = 0,
            interruptedByFailure = true,
            failureReason = QueueUploadFailureReason.HTTP_PERMANENT,
            httpStatusCode = 401,
        )

        assertEquals(SyncFailureClass.PERMANENT, result.failureClass)
        assertEquals(QueueUploadFailureReason.HTTP_PERMANENT, result.failureReason)
        assertEquals(401, result.httpStatusCode)
    }

    @Test
    fun lastPointSentAtMsAfterRowsDeleted_visibleRowsSentRefreshesTimestamp() {
        assertEquals(
            20_000L,
            QueueUploadOutcomePolicy.lastPointSentAtMsAfterRowsDeleted(
                previousLastPointSentAtMs = 5_000L,
                visibleRowsSent = 2,
                uploadedAtMs = 20_000L,
            ),
        )
    }

    @Test
    fun lastPointSentAtMsAfterRowsDeleted_backlogOnlyKeepsPreviousTimestamp() {
        assertEquals(
            5_000L,
            QueueUploadOutcomePolicy.lastPointSentAtMsAfterRowsDeleted(
                previousLastPointSentAtMs = 5_000L,
                visibleRowsSent = 0,
                uploadedAtMs = 20_000L,
            ),
        )
    }
}
