package com.geovault.tracker.runtime

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TrackingRuntimeFacade private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val repository = RuntimeStateRepository(appContext)
    private val telemetry = RuntimeTelemetry(appContext)
    private val scheduler = WatchdogScheduler(appContext)
    private val startGate = ServiceStartGate(appContext)
    private val effects = RuntimeEffectDispatcher(appContext, scheduler, startGate)
    private val healthPolicy = RuntimeHealthPolicy(appContext)
    private val stateMachine = RuntimeStateMachine()
    private val commandHandler = RuntimeCommandHandler(
        repository = repository,
        stateMachine = stateMachine,
        healthPolicy = healthPolicy,
        effects = effects,
        telemetry = telemetry
    )

    private val _state = MutableStateFlow(TrackingSessionState(runtime = repository.readState()))
    val state: StateFlow<TrackingSessionState> = _state.asStateFlow()

    init {
        val reconciled = healthPolicy.reconcileState(
            current = _state.value.runtime,
            isServiceRunning = repository.isServiceRunning(),
            reason = "facade_init"
        )
        if (reconciled != _state.value.runtime) {
            telemetry.transition("reconcile:facade_init", _state.value.runtime, reconciled)
            val persisted = repository.updateState { reconciled }
            _state.value = _state.value.copy(runtime = persisted)
        }
    }

    fun handleCommand(command: RuntimeCommand): TrackingSessionUpdateResult {
        val result = commandHandler.handleCommand(_state.value, command)
        _state.value = result.state
        return result
    }

    fun handleWatchdogTick(
        restartTrackingIfKilled: Boolean,
        wasTrackingBeforeExit: Boolean
    ): TrackingSessionUpdateResult {
        val result = commandHandler.handleWatchdogTick(
            current = _state.value,
            restartTrackingIfKilled = restartTrackingIfKilled,
            wasTrackingBeforeExit = wasTrackingBeforeExit
        )
        _state.value = result.state
        return result
    }

    fun handleServiceEvent(event: RuntimeServiceEvent): TrackingSessionUpdateResult {
        val result = commandHandler.handleServiceEvent(_state.value, event)
        _state.value = result.state
        return result
    }

    fun scheduleWatchdog(reason: String = "explicit_schedule") {
        commandHandler.scheduleWatchdog()
        telemetry.decision("schedule_watchdog", "reason=$reason")
    }

    fun cancelWatchdog(reason: String = "explicit_cancel") {
        commandHandler.cancelWatchdog()
        telemetry.decision("cancel_watchdog", "reason=$reason")
    }

    companion object {
        @Volatile
        private var INSTANCE: TrackingRuntimeFacade? = null

        fun get(context: Context): TrackingRuntimeFacade {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TrackingRuntimeFacade(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
