package com.geovault.tracker.startup

import android.app.Application
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.TrackingRecoveryCoordinator
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.runtime.TrackingRuntimeController
import com.geovault.tracker.settings.TrackerSettingsLoadState
import com.geovault.tracker.settings.TrackerSettingsRepository
import com.geovault.tracker.settings.TrackerSettingsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class ColdStartArmDecision(val logLabel: String) {
    Rearm("rearm"),
    SkipNotReady("skip_not_ready"),
    SkipNoPreviousSession("skip_no_previous_session"),
    SkipServiceAlreadyRunning("skip_service_already_running")
}

/**
 * Pure decision function for cold-start re-arm behavior. Exposed for testing.
 */
object ColdStartArmPolicy {
    fun decide(state: TrackerSettingsState, isServiceRunning: Boolean): ColdStartArmDecision {
        if (state.loadState != TrackerSettingsLoadState.Ready) return ColdStartArmDecision.SkipNotReady
        if (!state.wasTrackingBeforeExit) return ColdStartArmDecision.SkipNoPreviousSession
        if (isServiceRunning) return ColdStartArmDecision.SkipServiceAlreadyRunning
        return ColdStartArmDecision.Rearm
    }
}

/**
 * Re-arms the recovery watchdog at cold start when the previous process died mid-tracking.
 *
 * Boot, in-process unexpected destroy, and explicit user actions all schedule the watchdog
 * directly. This handles the residual case where Android cold-starts the process for any
 * reason after a crash and nothing else fires.
 */
class WatchdogColdStartArmer internal constructor(
    private val settings: TrackerSettingsRepository,
    private val isServiceRunning: () -> Boolean,
    private val schedule: () -> Unit,
    private val scope: CoroutineScope
) {
    constructor(application: Application) : this(
        settings = TrackerAppServices.from(application).trackerSettingsRepository(),
        isServiceRunning = { TrackingRuntimeController.get(application).isServiceRunning() },
        schedule = { TrackingRecoveryCoordinator.ensureWatchdogScheduled(application) },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    )

    fun start() {
        scope.launch {
            val state = settings.observeState()
                .first { it.loadState != TrackerSettingsLoadState.Loading }
            val decision = ColdStartArmPolicy.decide(state, isServiceRunning())
            GeoVaultCaptureLog.i(TAG, "Cold-start arm decision=${decision.logLabel}")
            if (decision == ColdStartArmDecision.Rearm) {
                schedule()
            }
        }
    }

    companion object {
        private const val TAG = "WatchdogColdStartArmer"
    }
}
