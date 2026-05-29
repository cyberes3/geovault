package com.geovault.common.logging

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureLogThrottleTest {

    @After
    fun tearDown() {
        CaptureLogThrottle.resetForTests()
    }

    @Test
    fun shouldLogInterval_blocksUntilElapsed() {
        assertTrue(CaptureLogThrottle.shouldLogInterval("k", intervalMs = 1_000L, nowMs = 0L))
        assertFalse(CaptureLogThrottle.shouldLogInterval("k", intervalMs = 1_000L, nowMs = 500L))
        assertTrue(CaptureLogThrottle.shouldLogInterval("k", intervalMs = 1_000L, nowMs = 1_000L))
    }

    @Test
    fun shouldLogOnChange_onlyWhenSignatureChanges() {
        assertTrue(CaptureLogThrottle.shouldLogOnChange("k", "a"))
        assertFalse(CaptureLogThrottle.shouldLogOnChange("k", "a"))
        assertTrue(CaptureLogThrottle.shouldLogOnChange("k", "b"))
    }
}
