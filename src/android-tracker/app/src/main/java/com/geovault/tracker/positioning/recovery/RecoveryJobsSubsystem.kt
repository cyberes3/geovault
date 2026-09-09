package com.geovault.tracker.positioning.recovery
import com.geovault.tracker.positioning.PositioningRuntime
import com.geovault.tracker.runtime.TrackerRuntimeCommands
import com.geovault.tracker.runtime.TrackerRuntimeEngine
import com.geovault.tracker.tracking.TrackingServiceConstants
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class RecoveryJobsSubsystem(private val rt: PositioningRuntime) {
    fun startRecoveryHeartbeat() {
        rt.state.recoveryHeartbeatJob?.cancel()
        rt.state.recoveryHeartbeatJob = rt.serviceScope.launch {
            while (rt.state.isTracking) {
                TrackerRuntimeEngine.get(rt.ports.service.applicationContext).handle(
                    TrackerRuntimeCommands.Heartbeat(),
                )
                delay(TrackingServiceConstants.RECOVERY_HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    fun stopRecoveryHeartbeat() {
        rt.state.recoveryHeartbeatJob?.cancel()
        rt.state.recoveryHeartbeatJob = null
    }

}
