package com.geovault.tracker

import com.geovault.tracker.location.SyncFailureClass
import com.geovault.tracker.services.QueueUploadResult
import com.geovault.tracker.services.QueueUploadSkipReason
import com.geovault.tracker.services.UploadLivenessState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingServiceUploadCharacterizationTest {
    @Test
    fun skippedUploadDoesNotIncreaseFailurePosture() {
        val before = UploadLivenessState(
            lastFailureClass = SyncFailureClass.TRANSIENT,
            consecutiveFailures = 2,
        )

        val after = before.onUploadResult(
            result = QueueUploadResult(
                failureClass = SyncFailureClass.SKIPPED,
                skippedReason = QueueUploadSkipReason.LOCK_BUSY,
            ),
            nowMs = 10_000L,
            updateFailureCounters = true,
        )

        assertEquals(before.lastFailureClass, after.lastFailureClass)
        assertEquals(before.consecutiveFailures, after.consecutiveFailures)
        assertTrue(after.hasUploadTrouble)
    }

    @Test
    fun visibleUploadSuccessRefreshesUploadLiveness() {
        val after = UploadLivenessState(
            lastFailureClass = SyncFailureClass.TRANSIENT,
            consecutiveFailures = 1,
        ).onUploadResult(
            result = QueueUploadResult(
                failureClass = SyncFailureClass.NONE,
                batchesAttempted = 1,
                batchesSent = 1,
                rowsDeleted = 3,
                visibleRowsSent = 2,
            ),
            nowMs = 20_000L,
            updateFailureCounters = true,
        )

        assertEquals(20_000L, after.lastSucceededAtMs)
        assertEquals(SyncFailureClass.NONE, after.lastFailureClass)
        assertEquals(0, after.consecutiveFailures)
        assertFalse(after.hasUploadTrouble)
    }

    @Test
    fun permanentInvalidTrackerResultKeepsRecordingAndUploadHealthSeparate() {
        val after = UploadLivenessState().onUploadResult(
            result = QueueUploadResult(
                failureClass = SyncFailureClass.PERMANENT,
                skippedReason = QueueUploadSkipReason.INVALID_TRACKER,
            ),
            nowMs = 30_000L,
            updateFailureCounters = true,
        )

        assertEquals(SyncFailureClass.PERMANENT, after.lastFailureClass)
        assertEquals(1, after.consecutiveFailures)
        assertTrue(after.hasUploadTrouble)
    }
}
