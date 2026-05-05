package com.geovault.tracker.runtime

import com.geovault.tracker.services.TrackingRuntimeStateStore

internal interface RuntimeServiceStateProvider {
    fun isServiceStartingOrUsable(): Boolean
    fun isSessionActive(): Boolean
    fun isStartupActive(): Boolean
}

internal class RuntimeServiceStateProbe(
    private val provider: RuntimeServiceStateProvider = DefaultRuntimeServiceStateProvider
) {
    fun isServiceRunningOrStarting(): Boolean {
        return provider.isServiceStartingOrUsable() ||
            provider.isSessionActive() ||
            provider.isStartupActive()
    }
}

private object DefaultRuntimeServiceStateProvider : RuntimeServiceStateProvider {
    override fun isServiceStartingOrUsable(): Boolean = TrackingServiceLifecycleGate.isServiceStartingOrUsable()

    override fun isSessionActive(): Boolean = TrackingRuntimeStateStore.state.value.sessionActive

    override fun isStartupActive(): Boolean = TrackingRuntimeStateStore.state.value.startupActive
}
