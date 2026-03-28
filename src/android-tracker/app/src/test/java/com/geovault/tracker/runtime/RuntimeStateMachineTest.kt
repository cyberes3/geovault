package com.geovault.tracker.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStateMachineTest {
    private val stateMachine = RuntimeStateMachine()

    @Test
    fun startCommand_dispatchesStart_whenServiceNotRunningEvenIfStateWasActive() {
        val decision = stateMachine.evaluateCommand(
            current = RuntimeState(
                lifecycleState = RuntimeLifecycleState.ACTIVE,
                shouldBeRunning = true
            ),
            command = RuntimeCommand(
                type = RuntimeCommandType.START,
                trigger = RuntimeTrigger.EXPLICIT_START,
                reason = "manual_start"
            ),
            isServiceRunning = false
        )

        assertEquals(RuntimeActionType.DISPATCH_START, decision.action.type)
        assertEquals(RuntimeLifecycleState.STARTING, decision.nextState.lifecycleState)
        assertTrue(decision.nextState.shouldBeRunning)
    }

    @Test
    fun startCommand_noops_whenServiceAlreadyRunning() {
        val decision = stateMachine.evaluateCommand(
            current = RuntimeState(
                lifecycleState = RuntimeLifecycleState.ACTIVE,
                shouldBeRunning = true
            ),
            command = RuntimeCommand(
                type = RuntimeCommandType.START,
                trigger = RuntimeTrigger.EXPLICIT_START,
                reason = "manual_start"
            ),
            isServiceRunning = true
        )

        assertEquals(RuntimeActionType.NOOP, decision.action.type)
        assertEquals(RuntimeLifecycleState.ACTIVE, decision.nextState.lifecycleState)
        assertTrue(decision.nextState.shouldBeRunning)
    }
}
