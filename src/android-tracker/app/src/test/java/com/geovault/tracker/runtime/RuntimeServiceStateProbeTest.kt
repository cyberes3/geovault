package com.geovault.tracker.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeServiceStateProbeTest {

    @Test
    fun runningWhenRuntimeStoreReportsRunning() {
        val probe = RuntimeServiceStateProbe(
            provider = StubRuntimeServiceStateProvider(
                runtimeStoreRunning = true,
                startupInProgress = false
            )
        )
        assertTrue(probe.isServiceRunningOrStarting())
    }

    @Test
    fun runningWhenStartupInProgressEvenWithoutRuntimeStoreRunning() {
        val probe = RuntimeServiceStateProbe(
            provider = StubRuntimeServiceStateProvider(
                runtimeStoreRunning = false,
                startupInProgress = true
            )
        )
        assertTrue(probe.isServiceRunningOrStarting())
    }

    @Test
    fun notRunningWhenNeitherSignalPresent() {
        val probe = RuntimeServiceStateProbe(
            provider = StubRuntimeServiceStateProvider(
                runtimeStoreRunning = false,
                startupInProgress = false
            )
        )
        assertFalse(probe.isServiceRunningOrStarting())
    }
}

private data class StubRuntimeServiceStateProvider(
    private val runtimeStoreRunning: Boolean,
    private val startupInProgress: Boolean
) : RuntimeServiceStateProvider {
    override fun isRuntimeStoreRunning(): Boolean = runtimeStoreRunning

    override fun isStartupInProgress(): Boolean = startupInProgress
}
