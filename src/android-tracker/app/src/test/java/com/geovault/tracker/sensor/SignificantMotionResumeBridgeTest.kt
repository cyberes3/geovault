package com.geovault.tracker.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SignificantMotionResumeBridge] with [FakeSignificantMotionTrigger].
 * Verifies that request/cancel and trigger firing correctly invoke the resume callback.
 */
class SignificantMotionResumeBridgeTest {

    @Test
    fun requestThenSimulateTrigger_invokesOnResume() {
        val fake = FakeSignificantMotionTrigger()
        var resumeCalled = false
        val bridge = SignificantMotionResumeBridge(fake) { resumeCalled = true }

        bridge.request()
        assertFalse(resumeCalled)
        fake.simulateTrigger()
        assertTrue(resumeCalled)
    }

    @Test
    fun cancelThenSimulateTrigger_doesNotInvokeOnResume() {
        val fake = FakeSignificantMotionTrigger()
        var resumeCalled = false
        val bridge = SignificantMotionResumeBridge(fake) { resumeCalled = true }

        bridge.request()
        bridge.cancel()
        fake.simulateTrigger()
        assertFalse(resumeCalled)
    }

    @Test
    fun requestAfterCancel_thenSimulateTrigger_invokesOnResumeOnce() {
        val fake = FakeSignificantMotionTrigger()
        var resumeCallCount = 0
        val bridge = SignificantMotionResumeBridge(fake) { resumeCallCount++ }

        bridge.request()
        bridge.cancel()
        bridge.request()
        fake.simulateTrigger()
        assertEquals("resume should be called once", 1, resumeCallCount)
    }

    @Test
    fun simulateTrigger_oneShot_secondSimulateDoesNothing() {
        val fake = FakeSignificantMotionTrigger()
        var resumeCallCount = 0
        val bridge = SignificantMotionResumeBridge(fake) { resumeCallCount++ }

        bridge.request()
        fake.simulateTrigger()
        fake.simulateTrigger()
        assertEquals("resume should be called only once (one-shot)", 1, resumeCallCount)
    }
}
