package com.geovault.tracker

import android.app.AlarmManager
import com.geovault.tracker.settings.TrackerSettingsLoadState
import com.geovault.tracker.startup.RecoveryStartupPolicy
import com.geovault.tracker.startup.RecoveryStartupSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingRecoveryReceiverTest {
    @Test
    fun recoveryStartupPolicy_requiresSupportedActionReadyStateAndPreviousSession() {
        val unsupported = RecoveryStartupPolicy.evaluate(
            RecoveryStartupSnapshot(
                action = "other_action",
                settingsLoadState = TrackerSettingsLoadState.Ready,
                wasTrackingBeforeExit = true
            )
        )
        assertFalse(unsupported.shouldHandleTick)

        val loading = RecoveryStartupPolicy.evaluate(
            RecoveryStartupSnapshot(
                action = TrackingRecoveryCoordinator.ACTION_RECOVERY_TICK,
                settingsLoadState = TrackerSettingsLoadState.Loading,
                wasTrackingBeforeExit = true
            )
        )
        assertFalse(loading.shouldHandleTick)

        val noPrevious = RecoveryStartupPolicy.evaluate(
            RecoveryStartupSnapshot(
                action = AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
                settingsLoadState = TrackerSettingsLoadState.Ready,
                wasTrackingBeforeExit = false
            )
        )
        assertFalse(noPrevious.shouldHandleTick)

        val ready = RecoveryStartupPolicy.evaluate(
            RecoveryStartupSnapshot(
                action = TrackingRecoveryCoordinator.ACTION_RECOVERY_TICK,
                settingsLoadState = TrackerSettingsLoadState.Ready,
                wasTrackingBeforeExit = true
            )
        )
        assertTrue(ready.shouldHandleTick)
        assertTrue(ready.request?.shouldAttemptRecovery == true)
    }
}
