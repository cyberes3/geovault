package com.geovault.tracker.positioning.motion

import com.geovault.common.concurrent.GeoVaultStateStore
import com.geovault.tracker.positioning.GpsProviderWaitPolicy
import com.geovault.tracker.positioning.PositioningRuntime
import com.geovault.tracker.positioning.config.GpsRuntimeState
import kotlinx.coroutines.flow.StateFlow

sealed class MotionState {
    data object Collecting : MotionState()
    data object PausedStationary : MotionState()
    data object ProbingFreshness : MotionState()
    data object WaitingForProvider : MotionState()
}

internal fun motionStateOf(
    gpsRuntimeState: GpsRuntimeState,
    probeActive: Boolean,
): MotionState {
    if (GpsProviderWaitPolicy.isWaitingForProviderState(gpsRuntimeState)) {
        return MotionState.WaitingForProvider
    }
    if (probeActive) {
        return MotionState.ProbingFreshness
    }
    if (gpsRuntimeState == GpsRuntimeState.PAUSED_FOR_MOTION) {
        return MotionState.PausedStationary
    }
    return MotionState.Collecting
}

sealed class PauseIntent {
    data object Stationary : PauseIntent()
    data object FreshnessForce : PauseIntent()
}

sealed class ResumeIntent {
    data object SignificantMotion : ResumeIntent()
    data object ImuVehicular : ResumeIntent()
    data object FreshnessProbe : ResumeIntent()
    data object ProviderRestored : ResumeIntent()
    data class ProviderWait(val reason: String) : ResumeIntent()
}

/**
 * Sole caller of GPS pause/resume. Reducers still decide *when*; this Engine is the only
 * hardware command path. [MotionState] is the published lookup for pause / probe / collect / wait.
 */
internal class MotionOrchestrator(private val rt: PositioningRuntime) {
    private val motion = GeoVaultStateStore(motionStateOf(rt.state.gpsRuntimeState, probeActive = false))
    val state: StateFlow<MotionState> = motion.state

    fun current(): MotionState = derive()

    fun pause(intent: PauseIntent) {
        val force = intent is PauseIntent.FreshnessForce
        rt.collection.pauseGpsInternal(force = force)
        publish()
    }

    fun resume(intent: ResumeIntent) {
        when (intent) {
            ResumeIntent.SignificantMotion -> rt.collection.onSignificantMotion()
            ResumeIntent.ImuVehicular -> rt.collection.resumeGps("imu_vehicular_wake")
            ResumeIntent.FreshnessProbe -> rt.collection.resumeGps("stationary_ping_resume")
            ResumeIntent.ProviderRestored -> rt.collection.resumeFromGpsProviderWait("provider_restored")
            is ResumeIntent.ProviderWait -> rt.collection.resumeFromGpsProviderWait(intent.reason)
        }
        publish()
    }

    fun onFixIngested() {
        publish()
    }

    private fun publish() {
        motion.replace(derive())
    }

    private fun derive(): MotionState {
        val probeActive = runCatching { rt.deps.stationaryFreshnessCoordinator.probeActive }
            .getOrDefault(false)
        return motionStateOf(rt.state.gpsRuntimeState, probeActive)
    }
}
