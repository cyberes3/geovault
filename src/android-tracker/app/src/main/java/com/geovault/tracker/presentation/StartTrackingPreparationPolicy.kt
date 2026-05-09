package com.geovault.tracker.presentation

import com.geovault.tracker.runtime.RuntimeCommandResult
import com.geovault.tracker.services.TrackingRuntimeSnapshot

internal object StartTrackingPreparationPolicy {
    @JvmStatic
    fun shouldClearAfterStartCommand(result: RuntimeCommandResult): Boolean {
        return result.startGateDecision?.allowed != true
    }

    @JvmStatic
    fun shouldClearForRuntime(runtime: TrackingRuntimeSnapshot): Boolean {
        return runtime.sessionActive ||
            runtime.startupActive ||
            (!runtime.isRunning && !runtime.failureReason.isNullOrBlank())
    }
}
