package com.geovault.tracker.runtime

import com.geovault.tracker.location.SyncFailureClass
import com.geovault.tracker.services.GpsRuntimeState
import com.geovault.tracker.services.ProviderHealthDecision
import com.geovault.tracker.services.QueueUploadFailureReason
import com.geovault.tracker.services.QueueUploadResult
import com.geovault.tracker.services.QueueUploadScope
import com.geovault.tracker.services.TrackingMotionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PositioningDiagnosticEventTest {
    @Test
    fun providerHealthUsesStableKebabReason() {
        val event = PositioningDiagnosticEvent.providerHealth(
            ProviderHealthDecision.ReapplyRequest(ageMs = 91_000L)
        )

        assertEquals("provider_health", event.first)
        assertEquals("reason=callback-silent:ageMs=91000", event.second)
    }

    @Test
    fun snapshotFormatsCompactRuntimeState() {
        val event = PositioningDiagnosticEvent.snapshot(
            PositioningDiagnosticSnapshot(
                gpsState = GpsRuntimeState.RUNNING,
                motionMode = TrackingMotionMode.BIKING,
                providerHealth = "healthy",
                localAgeMs = 1_000L,
                uploadAgeMs = null,
                recoveryProbe = "inactive",
                stationaryRegion = "inactive",
                queueCount = 3,
                uploadFailureClass = SyncFailureClass.NONE,
            )
        )

        assertEquals("positioning_diagnostic_snapshot", event.first)
        assertTrue(event.second.contains("gpsState=RUNNING"))
        assertTrue(event.second.contains("queueCount=3"))
    }

    @Test
    fun queueUploadResultFormatsTypedOutcome() {
        val event = PositioningDiagnosticEvent.queueUploadResult(
            result = QueueUploadResult(
                failureClass = SyncFailureClass.TRANSIENT,
                batchesAttempted = 2,
                batchesSent = 1,
                rowsDeleted = 50,
                visibleRowsSent = 12,
                interruptedByFailure = true,
                failureReason = QueueUploadFailureReason.HTTP_TRANSIENT,
                httpStatusCode = 503,
            ),
            scope = QueueUploadScope.LIVE_ONLY,
        )

        assertEquals("queue_upload_result", event.first)
        assertTrue(event.second.contains("visibleRowsSent=12"))
        assertTrue(event.second.contains("failureReason=http-transient"))
    }
}
