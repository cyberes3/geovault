package com.geovault.tracker.presentation

import com.geovault.tracker.runtime.RuntimeActionType
import com.geovault.tracker.runtime.RuntimeCommandResult
import com.geovault.tracker.runtime.StartGateDecision
import com.geovault.tracker.services.RecordingRuntime
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartTrackingPreparationPolicyTest {

    @Test
    fun shouldClearAfterStartCommand_keepsPreparingWhenStartGateAccepted() {
        val result = RuntimeCommandResult(
            action = RuntimeActionType.DISPATCH_START,
            reason = "start",
            startGateDecision = StartGateDecision(allowed = true, reason = "start_dispatched")
        )

        assertFalse(StartTrackingPreparationPolicy.shouldClearAfterStartCommand(result))
    }

    @Test
    fun shouldClearAfterStartCommand_clearsWhenStartGateDenied() {
        val result = RuntimeCommandResult(
            action = RuntimeActionType.DISPATCH_START,
            reason = "start",
            startGateDecision = StartGateDecision(allowed = false, reason = "blocked_backoff")
        )

        assertTrue(StartTrackingPreparationPolicy.shouldClearAfterStartCommand(result))
    }

    @Test
    fun shouldClearAfterStartCommand_clearsWhenNoStartWasDispatched() {
        val result = RuntimeCommandResult(
            action = RuntimeActionType.NOOP,
            reason = "already_active"
        )

        assertTrue(StartTrackingPreparationPolicy.shouldClearAfterStartCommand(result))
    }

    @Test
    fun shouldClearForRuntime_keepsPreparingWhileRuntimeStillStoppedWithoutFailure() {
        val runtime = TrackingRuntimeSnapshot(isRunning = false)

        assertFalse(StartTrackingPreparationPolicy.shouldClearForRuntime(runtime))
    }

    @Test
    fun shouldClearForRuntime_clearsWhenRuntimeReportsStartupActive() {
        val runtime = TrackingRuntimeSnapshot(
            isRunning = true,
            recordingRuntime = RecordingRuntime(startupActive = true)
        )

        assertTrue(StartTrackingPreparationPolicy.shouldClearForRuntime(runtime))
    }

    @Test
    fun shouldClearForRuntime_clearsWhenRuntimeReportsSessionActive() {
        val runtime = TrackingRuntimeSnapshot(
            isRunning = true,
            recordingRuntime = RecordingRuntime(sessionActive = true)
        )

        assertTrue(StartTrackingPreparationPolicy.shouldClearForRuntime(runtime))
    }

    @Test
    fun shouldClearForRuntime_clearsWhenStoppedRuntimeCarriesFailure() {
        val runtime = TrackingRuntimeSnapshot(
            isRunning = false,
            failureReason = "Unable to start"
        )

        assertTrue(StartTrackingPreparationPolicy.shouldClearForRuntime(runtime))
    }
}
