package com.geovault.tracker

import android.app.AlarmManager
import com.geovault.tracker.settings.TrackerSettingsLoadState
import com.geovault.tracker.startup.RecoveryStartupPolicy
import com.geovault.tracker.startup.RecoveryStartupSnapshot
import com.geovault.tracker.startup.RecoveryTickOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingRecoveryReceiverTest {
    @Test
    fun unsupportedAction_returnsStop() {
        val outcome = RecoveryStartupPolicy.evaluate(
            RecoveryStartupSnapshot(
                action = "other_action",
                settingsLoadState = TrackerSettingsLoadState.Ready,
                wasTrackingBeforeExit = true
            )
        )
        assertTrue(outcome is RecoveryTickOutcome.Stop)
        assertEquals("unsupported_action", (outcome as RecoveryTickOutcome.Stop).reason)
    }

    @Test
    fun loadingSettings_returnsDeferWithLoadingDelay() {
        val outcome = RecoveryStartupPolicy.evaluate(
            RecoveryStartupSnapshot(
                action = TrackingRecoveryCoordinator.ACTION_RECOVERY_TICK,
                settingsLoadState = TrackerSettingsLoadState.Loading,
                wasTrackingBeforeExit = true
            )
        )
        assertTrue(outcome is RecoveryTickOutcome.Defer)
        val defer = outcome as RecoveryTickOutcome.Defer
        assertEquals(RecoveryStartupPolicy.LOADING_RETRY_MS, defer.delayMs)
        assertEquals("settings_loading", defer.reason)
    }

    @Test
    fun errorSettings_returnsDeferWithLongerDelay() {
        val outcome = RecoveryStartupPolicy.evaluate(
            RecoveryStartupSnapshot(
                action = TrackingRecoveryCoordinator.ACTION_RECOVERY_TICK,
                settingsLoadState = TrackerSettingsLoadState.Error,
                wasTrackingBeforeExit = true
            )
        )
        assertTrue(outcome is RecoveryTickOutcome.Defer)
        val defer = outcome as RecoveryTickOutcome.Defer
        assertEquals(RecoveryStartupPolicy.SETTINGS_ERROR_RETRY_MS, defer.delayMs)
        assertEquals("settings_error", defer.reason)
    }

    @Test
    fun readyButNoPreviousSession_returnsStop() {
        val outcome = RecoveryStartupPolicy.evaluate(
            RecoveryStartupSnapshot(
                action = AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
                settingsLoadState = TrackerSettingsLoadState.Ready,
                wasTrackingBeforeExit = false
            )
        )
        assertTrue(outcome is RecoveryTickOutcome.Stop)
        assertEquals("no_previous_tracking_session", (outcome as RecoveryTickOutcome.Stop).reason)
    }

    @Test
    fun readyAndPreviousSession_returnsHandleWithRecoveryRequest() {
        val outcome = RecoveryStartupPolicy.evaluate(
            RecoveryStartupSnapshot(
                action = TrackingRecoveryCoordinator.ACTION_RECOVERY_TICK,
                settingsLoadState = TrackerSettingsLoadState.Ready,
                wasTrackingBeforeExit = true
            )
        )
        assertTrue(outcome is RecoveryTickOutcome.Handle)
        val handle = outcome as RecoveryTickOutcome.Handle
        assertTrue(handle.request.shouldAttemptRecovery)
        assertTrue(handle.request.restartTrackingIfKilled)
        assertTrue(handle.request.wasTrackingBeforeExit)
    }

    @Test
    fun exactAlarmPermissionChange_withReadyState_returnsHandle() {
        val outcome = RecoveryStartupPolicy.evaluate(
            RecoveryStartupSnapshot(
                action = AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
                settingsLoadState = TrackerSettingsLoadState.Ready,
                wasTrackingBeforeExit = true
            )
        )
        assertTrue(outcome is RecoveryTickOutcome.Handle)
    }
}
