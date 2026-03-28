package com.geovault.tracker.runtime

class LifecyclePolicyEngine {
    private val stateMachine = RuntimeStateMachine()

    fun evaluate(current: RuntimeState, command: RuntimeCommand): RuntimeDecision {
        // Legacy adapter preserved for compatibility with existing tests/callers.
        return stateMachine.evaluateCommand(
            current = current,
            command = command,
            isServiceRunning = false
        )
    }
}
