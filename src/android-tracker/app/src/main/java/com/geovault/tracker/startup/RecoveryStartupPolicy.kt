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

sealed class RecoveryTickOutcome {
    data class Handle(val request: WatchdogRecoveryRequest) : RecoveryTickOutcome()
    data class Defer(val delayMs: Long, val reason: String) : RecoveryTickOutcome()
    data class Stop(val reason: String) : RecoveryTickOutcome()
}

object RecoveryStartupPolicy {
    const val LOADING_RETRY_MS: Long = 5_000L
    const val SETTINGS_ERROR_RETRY_MS: Long = 30_000L

    private val supportedActions = setOf(
        TrackingRecoveryCoordinator.ACTION_RECOVERY_TICK,
        AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
    )

    fun evaluate(snapshot: RecoveryStartupSnapshot): RecoveryTickOutcome {
        if (snapshot.action !in supportedActions) {
            return RecoveryTickOutcome.Stop("unsupported_action")
        }
        return when (snapshot.settingsLoadState) {
            TrackerSettingsLoadState.Ready -> {
                if (!snapshot.wasTrackingBeforeExit) {
                    RecoveryTickOutcome.Stop("no_previous_tracking_session")
                } else {
                    RecoveryTickOutcome.Handle(
                        WatchdogRecoveryRequest(
                            restartTrackingIfKilled = true,
                            wasTrackingBeforeExit = true
                        )
                    )
                }
            }
            TrackerSettingsLoadState.Loading ->
                RecoveryTickOutcome.Defer(LOADING_RETRY_MS, "settings_loading")
            TrackerSettingsLoadState.Error ->
                RecoveryTickOutcome.Defer(SETTINGS_ERROR_RETRY_MS, "settings_error")
        }
    }
}
