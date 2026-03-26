package com.geovault.tracker.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LifecyclePolicyEngineTest {
    private val engine = LifecyclePolicyEngine()

    @Test
    fun evaluate_startFromIdle_dispatchesStartAndEntersStarting() {
        val decision = engine.evaluate(
            current = RuntimeState(lifecycleState = RuntimeLifecycleState.IDLE, shouldBeRunning = false),
            command = RuntimeCommand(
                type = RuntimeCommandType.START,
                trigger = RuntimeTrigger.EXPLICIT_START,
                reason = "explicit_start"
            )
        )

        assertEquals(RuntimeActionType.DISPATCH_START, decision.action.type)
        assertEquals(RuntimeLifecycleState.STARTING, decision.nextState.lifecycleState)
        assertTrue(decision.nextState.shouldBeRunning)
    }

    @Test
    fun evaluate_startWhenAlreadyActive_isNoopButKeepsDesiredRunning() {
        val decision = engine.evaluate(
            current = RuntimeState(lifecycleState = RuntimeLifecycleState.ACTIVE, shouldBeRunning = true),
            command = RuntimeCommand(
                type = RuntimeCommandType.START,
                trigger = RuntimeTrigger.PROCESS_RESTART,
                reason = "restart"
            )
        )

        assertEquals(RuntimeActionType.NOOP, decision.action.type)
        assertTrue(decision.nextState.shouldBeRunning)
    }

    @Test
    fun evaluate_recoverWhenNotDesiredRunning_skipsRecovery() {
        val decision = engine.evaluate(
            current = RuntimeState(lifecycleState = RuntimeLifecycleState.IDLE, shouldBeRunning = false),
            command = RuntimeCommand(
                type = RuntimeCommandType.RECOVER,
                trigger = RuntimeTrigger.WATCHDOG_TICK,
                reason = "tick"
            )
        )

        assertEquals(RuntimeActionType.NOOP, decision.action.type)
        assertEquals("recovery_skipped_not_desired", decision.action.reason)
    }

    @Test
    fun evaluate_taskRemovedWhenDesiredRunning_dispatchesRecoveringStart() {
        val decision = engine.evaluate(
            current = RuntimeState(lifecycleState = RuntimeLifecycleState.ACTIVE, shouldBeRunning = true),
            command = RuntimeCommand(
                type = RuntimeCommandType.TASK_REMOVED,
                trigger = RuntimeTrigger.TASK_REMOVED,
                reason = "task_removed"
            )
        )

        assertEquals(RuntimeActionType.DISPATCH_START, decision.action.type)
        assertEquals(RuntimeLifecycleState.RECOVERING, decision.nextState.lifecycleState)
    }
}
