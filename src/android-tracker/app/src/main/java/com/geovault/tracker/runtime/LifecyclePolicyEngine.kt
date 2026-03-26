package com.geovault.tracker.runtime

class LifecyclePolicyEngine {
    fun evaluate(current: RuntimeState, command: RuntimeCommand): RuntimeDecision {
        val now = System.currentTimeMillis()
        return when (command.type) {
            RuntimeCommandType.START -> {
                if (current.lifecycleState == RuntimeLifecycleState.ACTIVE || current.lifecycleState == RuntimeLifecycleState.STARTING) {
                    RuntimeDecision(
                        action = RuntimeAction(RuntimeActionType.NOOP, "already_running"),
                        nextState = current.copy(
                            shouldBeRunning = true,
                            lastStartTrigger = command.trigger,
                            lastTransitionAtMs = now
                        )
                    )
                } else {
                    RuntimeDecision(
                        action = RuntimeAction(RuntimeActionType.DISPATCH_START, command.reason),
                        nextState = current.copy(
                            lifecycleState = RuntimeLifecycleState.STARTING,
                            shouldBeRunning = true,
                            lastIntentionalStop = false,
                            lastStartTrigger = command.trigger,
                            lastTransitionAtMs = now,
                            lastFailure = null
                        )
                    )
                }
            }
            RuntimeCommandType.STOP -> {
                RuntimeDecision(
                    action = RuntimeAction(RuntimeActionType.DISPATCH_STOP, command.reason),
                    nextState = current.copy(
                        lifecycleState = RuntimeLifecycleState.STOPPING,
                        shouldBeRunning = false,
                        lastIntentionalStop = true,
                        lastTransitionAtMs = now
                    )
                )
            }
            RuntimeCommandType.RECOVER -> {
                if (!current.shouldBeRunning) {
                    RuntimeDecision(
                        action = RuntimeAction(RuntimeActionType.NOOP, "recovery_skipped_not_desired"),
                        nextState = current.copy(lastTransitionAtMs = now)
                    )
                } else {
                    RuntimeDecision(
                        action = RuntimeAction(RuntimeActionType.DISPATCH_START, "recovery:${command.reason}"),
                        nextState = current.copy(
                            lifecycleState = RuntimeLifecycleState.RECOVERING,
                            lastIntentionalStop = false,
                            lastStartTrigger = command.trigger,
                            lastTransitionAtMs = now
                        )
                    )
                }
            }
            RuntimeCommandType.RESHOW_FOREGROUND -> {
                RuntimeDecision(
                    action = RuntimeAction(RuntimeActionType.RESHOW_FOREGROUND, command.reason),
                    nextState = current.copy(lastTransitionAtMs = now)
                )
            }
            RuntimeCommandType.HEARTBEAT -> {
                RuntimeDecision(
                    action = RuntimeAction(RuntimeActionType.NOOP, "heartbeat"),
                    nextState = current.copy(
                        lifecycleState = if (current.shouldBeRunning) RuntimeLifecycleState.ACTIVE else current.lifecycleState,
                        lastHeartbeatAtMs = now,
                        lastTransitionAtMs = now
                    )
                )
            }
            RuntimeCommandType.TASK_REMOVED -> {
                if (current.shouldBeRunning) {
                    RuntimeDecision(
                        action = RuntimeAction(RuntimeActionType.DISPATCH_START, "task_removed_recover"),
                        nextState = current.copy(
                            lifecycleState = RuntimeLifecycleState.RECOVERING,
                            lastTransitionAtMs = now
                        )
                    )
                } else {
                    RuntimeDecision(
                        action = RuntimeAction(RuntimeActionType.NOOP, "task_removed_not_running"),
                        nextState = current.copy(lastTransitionAtMs = now)
                    )
                }
            }
        }
    }
}
