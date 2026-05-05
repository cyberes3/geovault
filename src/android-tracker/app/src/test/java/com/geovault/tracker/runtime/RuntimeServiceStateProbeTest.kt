package com.geovault.tracker.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeServiceStateProbeTest {

    @Test
    fun runningWhenSessionActive() {
        val probe = RuntimeServiceStateProbe(
            provider = StubRuntimeServiceStateProvider(
                serviceStartingOrUsable = false,
                sessionActive = true,
                startupActive = false
            )
        )
        assertTrue(probe.isServiceRunningOrStarting())
    }

    @Test
    fun runningWhenStartupActiveEvenWithoutSessionActive() {
        val probe = RuntimeServiceStateProbe(
            provider = StubRuntimeServiceStateProvider(
                serviceStartingOrUsable = false,
                sessionActive = false,
                startupActive = true
            )
        )
        assertTrue(probe.isServiceRunningOrStarting())
    }

    @Test
    fun notRunningWhenNeitherSignalPresent() {
        val probe = RuntimeServiceStateProbe(
            provider = StubRuntimeServiceStateProvider(
                serviceStartingOrUsable = false,
                sessionActive = false,
                startupActive = false
            )
        )
        assertFalse(probe.isServiceRunningOrStarting())
    }

    @Test
    fun runningWhenServiceIsStartingBeforeRuntimeSnapshotSyncs() {
        val probe = RuntimeServiceStateProbe(
            provider = StubRuntimeServiceStateProvider(
                serviceStartingOrUsable = true,
                sessionActive = false,
                startupActive = false
            )
        )
        assertTrue(probe.isServiceRunningOrStarting())
    }

    @Test
    fun lifecycleGateCountsStartingAndUsableButNotDestroying() {
        TrackingServiceLifecycleGate.resetForTests()
        assertFalse(TrackingServiceLifecycleGate.isServiceStartingOrUsable())

        TrackingServiceLifecycleGate.markStarting()
        assertTrue(TrackingServiceLifecycleGate.isServiceStartingOrUsable())

        TrackingServiceLifecycleGate.markUsable()
        assertTrue(TrackingServiceLifecycleGate.isServiceStartingOrUsable())

        TrackingServiceLifecycleGate.markDestroying()
        assertFalse(TrackingServiceLifecycleGate.isServiceStartingOrUsable())

        TrackingServiceLifecycleGate.markDestroyed()
        assertFalse(TrackingServiceLifecycleGate.isServiceStartingOrUsable())
    }
}

private data class StubRuntimeServiceStateProvider(
    private val serviceStartingOrUsable: Boolean,
    private val sessionActive: Boolean,
    private val startupActive: Boolean
) : RuntimeServiceStateProvider {
    override fun isServiceStartingOrUsable(): Boolean = serviceStartingOrUsable

    override fun isSessionActive(): Boolean = sessionActive

    override fun isStartupActive(): Boolean = startupActive
}
