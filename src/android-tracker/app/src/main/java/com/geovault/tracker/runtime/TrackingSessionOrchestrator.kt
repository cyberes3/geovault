package com.geovault.tracker.runtime

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/**
 * Authoritative runtime orchestrator for tracking lifecycle policy + effects.
 */
class TrackingSessionOrchestrator private constructor(context: Context) {
    private val runtimeFacade = TrackingRuntimeFacade.get(context.applicationContext)
    val state: StateFlow<TrackingSessionState> = runtimeFacade.state

    fun handleCommand(command: RuntimeCommand): TrackingSessionUpdateResult {
        return runtimeFacade.handleCommand(command)
    }

    fun handleWatchdogTick(restartTrackingIfKilled: Boolean, wasTrackingBeforeExit: Boolean): TrackingSessionUpdateResult {
        return runtimeFacade.handleWatchdogTick(restartTrackingIfKilled, wasTrackingBeforeExit)
    }

    fun handleServiceEvent(event: RuntimeServiceEvent): TrackingSessionUpdateResult {
        return runtimeFacade.handleServiceEvent(event)
    }

    fun scheduleWatchdog(reason: String = "explicit_schedule") {
        runtimeFacade.scheduleWatchdog(reason)
    }

    fun cancelWatchdog(reason: String = "explicit_cancel") {
        runtimeFacade.cancelWatchdog(reason)
    }

    companion object {
        @Volatile
        private var INSTANCE: TrackingSessionOrchestrator? = null

        fun get(context: Context): TrackingSessionOrchestrator {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TrackingSessionOrchestrator(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
