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
        assertEquals(SyncFailureClass.PERMANENT, QueueUploadOutcomePolicy.httpFailureClass(401))
    }

    @Test
    fun finalOutcome_partialFailureIsTransientNotSuccess() {
        assertEquals(
            SyncFailureClass.TRANSIENT,
            QueueUploadOutcomePolicy.finalOutcome(batchesSent = 1, interruptedByFailure = true)
        )
        assertEquals(
            SyncFailureClass.NONE,
            QueueUploadOutcomePolicy.finalOutcome(batchesSent = 1, interruptedByFailure = false)
        )
    }
}
