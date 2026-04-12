package com.geovault.tracker.startup

import android.app.AlarmManager
import com.geovault.tracker.TrackingRecoveryCoordinator
import com.geovault.tracker.runtime.WatchdogRecoveryRequest
import com.geovault.tracker.settings.TrackerSettingsLoadState

data class RecoveryStartupSnapshot(
    val action: String?,
    val settingsLoadState: TrackerSettingsLoadState,
    val wasTrackingBeforeExit: Boolean
)

enum class RecoveryStartupBlocker(val logLabel: String) {
    UnsupportedAction("unsupported_action"),
    SettingsNotReady("settings_not_ready"),
    NoPreviousTrackingSession("no_previous_tracking_session")
}

data class RecoveryStartupDecision(
    val shouldHandleTick: Boolean,
    val request: WatchdogRecoveryRequest?,
    val blockers: List<RecoveryStartupBlocker>
)

object RecoveryStartupPolicy {
    private val supportedActions = setOf(
        TrackingRecoveryCoordinator.ACTION_RECOVERY_TICK,
        AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
    )

    fun evaluate(snapshot: RecoveryStartupSnapshot): RecoveryStartupDecision {
        val blockers = mutableListOf<RecoveryStartupBlocker>()
        if (!supportedActions.contains(snapshot.action)) {
            blockers += RecoveryStartupBlocker.UnsupportedAction
        }
        if (snapshot.settingsLoadState != TrackerSettingsLoadState.Ready) {
            blockers += RecoveryStartupBlocker.SettingsNotReady
        }
        if (!snapshot.wasTrackingBeforeExit) {
            blockers += RecoveryStartupBlocker.NoPreviousTrackingSession
        }
        if (blockers.isNotEmpty()) {
            return RecoveryStartupDecision(
                shouldHandleTick = false,
                request = null,
                blockers = blockers
            )
        }
        return RecoveryStartupDecision(
            shouldHandleTick = true,
            request = WatchdogRecoveryRequest(
                restartTrackingIfKilled = true,
                wasTrackingBeforeExit = true
            ),
            blockers = emptyList()
        )
    }
}
