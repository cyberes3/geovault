package com.geovault.tracker.runtime

import com.geovault.tracker.location.TrackingLifecycleState

class RuntimeCommandHandler(
    private val repository: RuntimeStateAccessor,
    private val stateMachine: RuntimeStateMachine,
    private val healthPolicy: RuntimeHealthPolicy,
    private val effects: RuntimeEffects,
    private val telemetry: RuntimeTelemetry
) {
    fun handleCommand(
        current: TrackingSessionState,
        command: RuntimeCommand
    ): TrackingSessionUpdateResult {
        val reconciledCurrent = reconcileRuntimeState(current, reason = "handle_command:${command.type}")
        val isServiceRunning = repository.isServiceRunning()
        val decision = stateMachine.evaluateCommand(reconciledCurrent.runtime, command, isServiceRunning)
        telemetry.decision(
            name = "policy",
            details = "cmdType=${command.type} trigger=${command.trigger} cmdReason=${command.reason} action=${decision.action.type} actionReason=${decision.action.reason} serviceRunning=$isServiceRunning"
        )
        telemetry.transition("policy_apply", reconciledCurrent.runtime, decision.nextState)

        val persistedDecisionState = repository.updateState { decision.nextState }
        var next = reconciledCurrent.copy(runtime = persistedDecisionState)
        val emittedEffects = mutableListOf<RuntimeEffect>()
        val result = when (decision.action.type) {
            RuntimeActionType.DISPATCH_START -> {
                val gateDecision = effects.dispatchStart(command.trigger, decision.action.reason)
                emittedEffects += RuntimeEffect(RuntimeEffectType.DISPATCH_START, decision.action.reason)
                if (gateDecision.allowed) {
                    val after = repository.updateState {
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
                    emittedEffects += RuntimeEffect(RuntimeEffectType.SCHEDULE_WATCHDOG, "start_gate_success")
                    effects.scheduleWatchdog()
                } else {
                    val after = repository.updateState {
                        it.copy(
                            lifecycleState = RuntimeLifecycleState.DEGRADED,
                            lastFailure = RuntimeFailure(
                                clazz = if (gateDecision.reason.contains("failed")) {
                                    RuntimeFailureClass.TRANSIENT
                                } else {
                                    RuntimeFailureClass.POLICY_DENIED
                                },
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
                val after = repository.updateState {
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
                emittedEffects += RuntimeEffect(RuntimeEffectType.DISPATCH_STOP, decision.action.reason)
                emittedEffects += RuntimeEffect(RuntimeEffectType.CANCEL_WATCHDOG, "dispatch_stop")
                effects.dispatchStop()
                effects.cancelWatchdog()
                telemetry.decision("dispatch_stop", "reason=${decision.action.reason}")
                RuntimeCommandResult(action = decision.action.type, reason = decision.action.reason)
            }

            RuntimeActionType.RESHOW_FOREGROUND -> {
                emittedEffects += RuntimeEffect(RuntimeEffectType.RESHOW_FOREGROUND, decision.action.reason)
                effects.reshowForeground()
                telemetry.decision("reshow_foreground", "reason=${decision.action.reason}")
                RuntimeCommandResult(action = decision.action.type, reason = decision.action.reason)
            }

            RuntimeActionType.NOOP -> {
                emittedEffects += RuntimeEffect(RuntimeEffectType.NOOP, decision.action.reason)
                telemetry.decision("noop", "reason=${decision.action.reason}")
                RuntimeCommandResult(action = decision.action.type, reason = decision.action.reason)
            }
        }
        telemetry.event(
            name = "command",
            details = "type=${command.type} trigger=${command.trigger} action=${decision.action.type} reason=${decision.action.reason}"
        )
        return TrackingSessionUpdateResult(state = next, effects = emittedEffects, commandResult = result)
    }

    fun handleWatchdogTick(
        current: TrackingSessionState,
        restartTrackingIfKilled: Boolean,
        wasTrackingBeforeExit: Boolean
    ): TrackingSessionUpdateResult {
        val reconciledCurrent = reconcileRuntimeState(current, reason = "watchdog_tick")
        val desiredRunning = restartTrackingIfKilled && wasTrackingBeforeExit
        telemetry.decision(
            "watchdog_tick",
            "restartTrackingIfKilled=$restartTrackingIfKilled wasTrackingBeforeExit=$wasTrackingBeforeExit desiredRunning=$desiredRunning"
        )
        if (!desiredRunning) {
            if (repository.isServiceRunning()) {
                effects.cancelWatchdog()
                telemetry.event(
                    "watchdog",
                    "skip_disable_service_running restartTrackingIfKilled=$restartTrackingIfKilled wasTrackingBeforeExit=$wasTrackingBeforeExit"
                )
                return TrackingSessionUpdateResult(
                    state = reconciledCurrent,
                    effects = listOf(RuntimeEffect(RuntimeEffectType.CANCEL_WATCHDOG, "watchdog_disabled_service_running")),
                    commandResult = RuntimeCommandResult(
                        action = RuntimeActionType.NOOP,
                        reason = "watchdog_disabled_service_running"
                    )
                )
            }
            val after = repository.updateState {
                it.copy(
                    shouldBeRunning = false,
                    lifecycleState = RuntimeLifecycleState.IDLE,
                    lastIntentionalStop = false,
                    lastTransitionAtMs = System.currentTimeMillis()
                )
            }
            val next = reconciledCurrent.copy(runtime = after)
            effects.cancelWatchdog()
            telemetry.event(
                "watchdog",
                "disabled restartTrackingIfKilled=$restartTrackingIfKilled wasTrackingBeforeExit=$wasTrackingBeforeExit"
            )
            return TrackingSessionUpdateResult(
                state = next,
                effects = listOf(RuntimeEffect(RuntimeEffectType.CANCEL_WATCHDOG, "watchdog_disabled")),
                commandResult = RuntimeCommandResult(
                    action = RuntimeActionType.NOOP,
                    reason = "watchdog_disabled"
                )
            )
        }

        repository.updateState {
            it.copy(
                shouldBeRunning = true,
                lastIntentionalStop = false
            )
        }
        val stateForHealth = repository.readState()
        val health = healthPolicy.evaluateRecoveryHealth(stateForHealth)
        telemetry.event(
            "watchdog",
            "healthy=${health.isHealthy} shouldRecover=${health.shouldRecover} reason=${health.reason}"
        )
        val recovered = if (health.shouldRecover) {
            handleCommand(
                current = reconciledCurrent.copy(runtime = stateForHealth),
                command = RuntimeCommand(
                    type = RuntimeCommandType.RECOVER,
                    trigger = RuntimeTrigger.WATCHDOG_TICK,
                    reason = health.reason
                )
            )
        } else {
            TrackingSessionUpdateResult(
                state = reconciledCurrent.copy(runtime = stateForHealth),
                effects = listOf(RuntimeEffect(RuntimeEffectType.NOOP, health.reason)),
                commandResult = RuntimeCommandResult(action = RuntimeActionType.NOOP, reason = health.reason)
            )
        }
        effects.scheduleWatchdog()
        telemetry.decision(
            "watchdog_schedule",
            "scheduledNextTick=true resultAction=${recovered.commandResult?.action} resultReason=${recovered.commandResult?.reason}"
        )
        return recovered.copy(
            effects = recovered.effects + RuntimeEffect(RuntimeEffectType.SCHEDULE_WATCHDOG, "watchdog_tick")
        )
    }

    fun handleServiceEvent(
        current: TrackingSessionState,
        event: RuntimeServiceEvent
    ): TrackingSessionUpdateResult {
        val reconciledCurrent = reconcileRuntimeState(current, reason = "service_event:${event.type}")
        val next = when (event.type) {
            RuntimeServiceEventType.TRACKING_STARTED -> {
                val after = repository.updateState {
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
                reconciledCurrent.copy(
                    runtime = after,
                    trackingRunning = true,
                    trackingLifecycleState = TrackingLifecycleState.RUNNING,
                    lastServiceEvent = event.type,
                    lastServiceEventReason = event.reason,
                    lastServiceEventAtMs = event.timestampMs
                )
            }

            RuntimeServiceEventType.TRACKING_STOPPED -> {
                val after = repository.updateState {
                    it.copy(
                        lifecycleState = RuntimeLifecycleState.IDLE,
                        shouldBeRunning = false,
                        lastIntentionalStop = true,
                        lastTransitionAtMs = event.timestampMs
                    )
                }
                reconciledCurrent.copy(
                    runtime = after,
                    trackingRunning = false,
                    trackingLifecycleState = TrackingLifecycleState.STOPPED,
                    lastServiceEvent = event.type,
                    lastServiceEventReason = event.reason,
                    lastServiceEventAtMs = event.timestampMs
                )
            }

            RuntimeServiceEventType.STARTUP_FAILED -> {
                val after = repository.updateState {
                    it.copy(
                        lifecycleState = RuntimeLifecycleState.IDLE,
                        shouldBeRunning = false,
                        lastIntentionalStop = true,
                        lastFailure = RuntimeFailure(
                            clazz = RuntimeFailureClass.PREREQUISITE,
                            reason = event.reason
                        ),
                        lastTransitionAtMs = event.timestampMs
                    )
                }
                reconciledCurrent.copy(
                    runtime = after,
                    trackingRunning = false,
                    trackingLifecycleState = TrackingLifecycleState.STOPPED,
                    lastServiceEvent = event.type,
                    lastServiceEventReason = event.reason,
                    lastServiceEventAtMs = event.timestampMs
                )
            }

            RuntimeServiceEventType.HEARTBEAT -> {
                val after = repository.updateState {
                    it.copy(
                        lastHeartbeatAtMs = event.timestampMs,
                        lastTransitionAtMs = event.timestampMs,
                        lifecycleState = if (reconciledCurrent.trackingRunning) {
                            RuntimeLifecycleState.ACTIVE
                        } else {
                            it.lifecycleState
                        }
                    )
                }
                reconciledCurrent.copy(
                    runtime = after,
                    lastServiceEvent = event.type,
                    lastServiceEventReason = event.reason,
                    lastServiceEventAtMs = event.timestampMs
                )
            }

            RuntimeServiceEventType.UNEXPECTED_DESTROY -> {
                val after = repository.updateState {
                    it.copy(
                        lifecycleState = RuntimeLifecycleState.RECOVERING,
                        shouldBeRunning = true,
                        lastIntentionalStop = false,
                        lastTransitionAtMs = event.timestampMs
                    )
                }
                reconciledCurrent.copy(
                    runtime = after,
                    trackingRunning = false,
                    trackingLifecycleState = TrackingLifecycleState.STOPPED,
                    lastServiceEvent = event.type,
                    lastServiceEventReason = event.reason,
                    lastServiceEventAtMs = event.timestampMs
                )
            }
        }
        telemetry.event(
            name = "service_event",
            details = "type=${event.type} trigger=${event.trigger} reason=${event.reason} trackingRunning=${next.trackingRunning}"
        )
        val emittedEffects = when (event.type) {
            RuntimeServiceEventType.TRACKING_STARTED -> {
                effects.scheduleWatchdog()
                listOf(RuntimeEffect(RuntimeEffectType.SCHEDULE_WATCHDOG, "tracking_started"))
            }

            RuntimeServiceEventType.TRACKING_STOPPED,
            RuntimeServiceEventType.STARTUP_FAILED -> {
                effects.cancelWatchdog()
                listOf(RuntimeEffect(RuntimeEffectType.CANCEL_WATCHDOG, "tracking_stopped"))
            }

            RuntimeServiceEventType.UNEXPECTED_DESTROY -> {
                effects.scheduleWatchdog()
                listOf(RuntimeEffect(RuntimeEffectType.SCHEDULE_WATCHDOG, "unexpected_destroy"))
            }

            RuntimeServiceEventType.HEARTBEAT -> emptyList()
        }
        return TrackingSessionUpdateResult(state = next, effects = emittedEffects)
    }

    fun scheduleWatchdog() {
        effects.scheduleWatchdog()
    }

    fun cancelWatchdog() {
        effects.cancelWatchdog()
    }

    private fun reconcileRuntimeState(
        current: TrackingSessionState,
        reason: String
    ): TrackingSessionState {
        val serviceRunning = repository.isServiceRunning()
        val reconciledRuntime = healthPolicy.reconcileState(
            current = current.runtime,
            isServiceRunning = serviceRunning,
            reason = reason
        )
        if (reconciledRuntime == current.runtime) return current
        telemetry.transition("reconcile:$reason", current.runtime, reconciledRuntime)
        val persisted = repository.updateState { reconciledRuntime }
        return current.copy(runtime = persisted)
    }
}
