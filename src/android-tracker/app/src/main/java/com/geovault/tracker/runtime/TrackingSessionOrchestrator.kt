package com.geovault.tracker.runtime

import android.content.Context
import android.content.Intent
import com.geovault.tracker.TrackingService
import com.geovault.tracker.location.TrackingLifecycleState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Authoritative runtime orchestrator for tracking lifecycle policy + effects.
 */
class TrackingSessionOrchestrator private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val stateStore = RuntimeStateStore(appContext)
    private val telemetry = RuntimeTelemetry(appContext)
    private val policy = LifecyclePolicyEngine()
    private val startGate = ServiceStartGate(appContext)
    private val scheduler = WatchdogScheduler(appContext)
    private val supervisor = TrackingHealthSupervisor(appContext)

    private val _state = MutableStateFlow(
        TrackingSessionState(runtime = stateStore.read())
    )
    val state: StateFlow<TrackingSessionState> = _state.asStateFlow()

    fun handleCommand(command: RuntimeCommand): TrackingSessionUpdateResult {
        val current = _state.value
        val decision = policy.evaluate(current.runtime, command)
        telemetry.decision(
            name = "policy",
            details = "cmdType=${command.type} trigger=${command.trigger} cmdReason=${command.reason} action=${decision.action.type} actionReason=${decision.action.reason}"
        )
        telemetry.transition("policy_apply", current.runtime, decision.nextState)
        val persisted = stateStore.update { decision.nextState }
        var next = current.copy(runtime = persisted)
        val effects = mutableListOf<RuntimeEffect>()
        val result = when (decision.action.type) {
            RuntimeActionType.DISPATCH_START -> {
                val gateDecision = startGate.dispatchStart(command.trigger, decision.action.reason)
                if (gateDecision.allowed) {
                    val after = stateStore.update {
                        it.copy(
                            lifecycleState = RuntimeLifecycleState.ACTIVE,
                            shouldBeRunning = true,
                            lastIntentionalStop = false,
                            lastFailure = null,
                            lastTransitionAtMs = System.currentTimeMillis()
                        )
                    }
                    telemetry.transition("start_gate_success", next.runtime, after)
                    next = next.copy(
                        runtime = after,
                        trackingLifecycleState = TrackingLifecycleState.STARTING
                    )
                    effects += RuntimeEffect(RuntimeEffectType.DISPATCH_START, decision.action.reason)
                    effects += RuntimeEffect(RuntimeEffectType.SCHEDULE_WATCHDOG, "start_gate_success")
                } else {
                    val after = stateStore.update {
                        it.copy(
                            lifecycleState = RuntimeLifecycleState.DEGRADED,
                            lastFailure = RuntimeFailure(
                                clazz = if (gateDecision.reason.contains("failed")) RuntimeFailureClass.TRANSIENT else RuntimeFailureClass.POLICY_DENIED,
                                reason = gateDecision.reason
                            ),
                            lastTransitionAtMs = System.currentTimeMillis()
                        )
                    }
                    telemetry.transition("start_gate_failure", next.runtime, after)
                    next = next.copy(runtime = after)
                }
                telemetry.decision(
                    "start_gate",
                    "allowed=${gateDecision.allowed} retryInMs=${gateDecision.retryInMs} reason=${gateDecision.reason}"
                )
                RuntimeCommandResult(
                    action = decision.action.type,
                    reason = decision.action.reason,
                    startGateDecision = gateDecision
                )
            }
            RuntimeActionType.DISPATCH_STOP -> {
                val after = stateStore.update {
                    it.copy(
                        lifecycleState = RuntimeLifecycleState.IDLE,
                        shouldBeRunning = false,
                        lastIntentionalStop = true,
                        lastTransitionAtMs = System.currentTimeMillis()
                    )
                }
                next = next.copy(
                    runtime = after,
                    trackingRunning = false,
                    trackingLifecycleState = TrackingLifecycleState.STOPPED
                )
                effects += RuntimeEffect(RuntimeEffectType.DISPATCH_STOP, decision.action.reason)
                effects += RuntimeEffect(RuntimeEffectType.CANCEL_WATCHDOG, "dispatch_stop")
                telemetry.decision("dispatch_stop", "reason=${decision.action.reason}")
                RuntimeCommandResult(action = decision.action.type, reason = decision.action.reason)
            }
            RuntimeActionType.RESHOW_FOREGROUND -> {
                effects += RuntimeEffect(RuntimeEffectType.RESHOW_FOREGROUND, decision.action.reason)
                telemetry.decision("reshow_foreground", "reason=${decision.action.reason}")
                RuntimeCommandResult(action = decision.action.type, reason = decision.action.reason)
            }
            RuntimeActionType.NOOP -> {
                effects += RuntimeEffect(RuntimeEffectType.NOOP, decision.action.reason)
                telemetry.decision("noop", "reason=${decision.action.reason}")
                RuntimeCommandResult(action = decision.action.type, reason = decision.action.reason)
            }
        }
        _state.value = next
        applyEffects(effects)
        telemetry.event(
            name = "command",
            details = "type=${command.type} trigger=${command.trigger} action=${decision.action.type} reason=${decision.action.reason}"
        )
        return TrackingSessionUpdateResult(state = next, effects = effects, commandResult = result)
    }

    fun handleWatchdogTick(restartTrackingIfKilled: Boolean, wasTrackingBeforeExit: Boolean): TrackingSessionUpdateResult {
        val desiredRunning = restartTrackingIfKilled && wasTrackingBeforeExit
        telemetry.decision(
            "watchdog_tick",
            "restartTrackingIfKilled=$restartTrackingIfKilled wasTrackingBeforeExit=$wasTrackingBeforeExit desiredRunning=$desiredRunning"
        )
        if (!desiredRunning) {
            val after = stateStore.update {
                it.copy(
                    shouldBeRunning = false,
                    lifecycleState = RuntimeLifecycleState.IDLE,
                    lastIntentionalStop = true,
                    lastTransitionAtMs = System.currentTimeMillis()
                )
            }
            val next = _state.value.copy(
                runtime = after,
                trackingRunning = false,
                trackingLifecycleState = TrackingLifecycleState.STOPPED
            )
            _state.value = next
            val effects = listOf(RuntimeEffect(RuntimeEffectType.CANCEL_WATCHDOG, "watchdog_disabled"))
            applyEffects(effects)
            telemetry.event("watchdog", "disabled restartTrackingIfKilled=$restartTrackingIfKilled wasTrackingBeforeExit=$wasTrackingBeforeExit")
            return TrackingSessionUpdateResult(
                state = next,
                effects = effects,
                commandResult = RuntimeCommandResult(action = RuntimeActionType.NOOP, reason = "watchdog_disabled")
            )
        }
        stateStore.update {
            it.copy(
                shouldBeRunning = true,
                lastIntentionalStop = false
            )
        }
        val health = supervisor.evaluate(stateStore.read())
        telemetry.event("watchdog", "healthy=${health.isHealthy} shouldRecover=${health.shouldRecover} reason=${health.reason}")
        val recovered = if (health.shouldRecover) {
            handleCommand(
                RuntimeCommand(
                    type = RuntimeCommandType.RECOVER,
                    trigger = RuntimeTrigger.WATCHDOG_TICK,
                    reason = health.reason
                )
            )
        } else {
            TrackingSessionUpdateResult(
                state = _state.value,
                effects = listOf(RuntimeEffect(RuntimeEffectType.NOOP, health.reason)),
                commandResult = RuntimeCommandResult(action = RuntimeActionType.NOOP, reason = health.reason)
            )
        }
        applyEffects(listOf(RuntimeEffect(RuntimeEffectType.SCHEDULE_WATCHDOG, "watchdog_tick")))
        telemetry.decision(
            "watchdog_schedule",
            "scheduledNextTick=true resultAction=${recovered.commandResult?.action} resultReason=${recovered.commandResult?.reason}"
        )
        return recovered
    }

    fun handleServiceEvent(event: RuntimeServiceEvent): TrackingSessionUpdateResult {
        val current = _state.value
        val next = when (event.type) {
            RuntimeServiceEventType.TRACKING_STARTED -> {
                val after = stateStore.update {
                    it.copy(
                        lifecycleState = RuntimeLifecycleState.ACTIVE,
                        shouldBeRunning = true,
                        lastIntentionalStop = false,
                        lastStartTrigger = event.trigger,
                        lastFailure = null,
                        lastTransitionAtMs = event.timestampMs,
                        lastHeartbeatAtMs = event.timestampMs
                    )
                }
                current.copy(
                    runtime = after,
                    trackingRunning = true,
                    trackingLifecycleState = TrackingLifecycleState.RUNNING,
                    lastServiceEvent = event.type,
                    lastServiceEventReason = event.reason,
                    lastServiceEventAtMs = event.timestampMs
                )
            }
            RuntimeServiceEventType.TRACKING_STOPPED,
            RuntimeServiceEventType.STARTUP_FAILED -> {
                val after = stateStore.update {
                    it.copy(
                        lifecycleState = RuntimeLifecycleState.IDLE,
                        shouldBeRunning = false,
                        lastIntentionalStop = true,
                        lastTransitionAtMs = event.timestampMs
                    )
                }
                current.copy(
                    runtime = after,
                    trackingRunning = false,
                    trackingLifecycleState = TrackingLifecycleState.STOPPED,
                    lastServiceEvent = event.type,
                    lastServiceEventReason = event.reason,
                    lastServiceEventAtMs = event.timestampMs
                )
            }
            RuntimeServiceEventType.HEARTBEAT -> {
                val after = stateStore.update {
                    it.copy(
                        lastHeartbeatAtMs = event.timestampMs,
                        lastTransitionAtMs = event.timestampMs,
                        lifecycleState = if (current.trackingRunning) RuntimeLifecycleState.ACTIVE else it.lifecycleState
                    )
                }
                current.copy(
                    runtime = after,
                    lastServiceEvent = event.type,
                    lastServiceEventReason = event.reason,
                    lastServiceEventAtMs = event.timestampMs
                )
            }
            RuntimeServiceEventType.UNEXPECTED_DESTROY -> {
                val after = stateStore.update {
                    it.copy(
                        lifecycleState = RuntimeLifecycleState.RECOVERING,
                        shouldBeRunning = true,
                        lastIntentionalStop = false,
                        lastTransitionAtMs = event.timestampMs
                    )
                }
                current.copy(
                    runtime = after,
                    trackingRunning = false,
                    trackingLifecycleState = TrackingLifecycleState.STOPPED,
                    lastServiceEvent = event.type,
                    lastServiceEventReason = event.reason,
                    lastServiceEventAtMs = event.timestampMs
                )
            }
        }
        _state.value = next
        telemetry.event(
            name = "service_event",
            details = "type=${event.type} trigger=${event.trigger} reason=${event.reason} trackingRunning=${next.trackingRunning}"
        )
        val effects = when (event.type) {
            RuntimeServiceEventType.TRACKING_STARTED -> listOf(RuntimeEffect(RuntimeEffectType.SCHEDULE_WATCHDOG, "tracking_started"))
            RuntimeServiceEventType.TRACKING_STOPPED,
            RuntimeServiceEventType.STARTUP_FAILED -> listOf(RuntimeEffect(RuntimeEffectType.CANCEL_WATCHDOG, "tracking_stopped"))
            RuntimeServiceEventType.UNEXPECTED_DESTROY -> listOf(RuntimeEffect(RuntimeEffectType.SCHEDULE_WATCHDOG, "unexpected_destroy"))
            RuntimeServiceEventType.HEARTBEAT -> emptyList()
        }
        applyEffects(effects)
        return TrackingSessionUpdateResult(state = next, effects = effects)
    }

    fun scheduleWatchdog(reason: String = "explicit_schedule") {
        applyEffects(listOf(RuntimeEffect(RuntimeEffectType.SCHEDULE_WATCHDOG, reason)))
    }

    fun cancelWatchdog(reason: String = "explicit_cancel") {
        applyEffects(listOf(RuntimeEffect(RuntimeEffectType.CANCEL_WATCHDOG, reason)))
    }

    private fun applyEffects(effects: List<RuntimeEffect>) {
        effects.forEach { effect ->
            when (effect.type) {
                RuntimeEffectType.DISPATCH_START -> {
                    appContext.startService(
                        Intent(appContext, TrackingService::class.java).apply {
                            action = TrackingService.ACTION_START
                            setPackage(appContext.packageName)
                        }
                    )
                }
                RuntimeEffectType.DISPATCH_STOP -> {
                    appContext.startService(
                        Intent(appContext, TrackingService::class.java).apply {
                            action = TrackingService.ACTION_STOP
                            setPackage(appContext.packageName)
                        }
                    )
                }
                RuntimeEffectType.RESHOW_FOREGROUND -> {
                    appContext.startService(
                        Intent(appContext, TrackingService::class.java).apply {
                            action = TrackingService.ACTION_RESHOW_FOREGROUND
                            setPackage(appContext.packageName)
                        }
                    )
                }
                RuntimeEffectType.SCHEDULE_WATCHDOG -> scheduler.schedule()
                RuntimeEffectType.CANCEL_WATCHDOG -> scheduler.cancel()
                RuntimeEffectType.NOOP -> Unit
            }
        }
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
