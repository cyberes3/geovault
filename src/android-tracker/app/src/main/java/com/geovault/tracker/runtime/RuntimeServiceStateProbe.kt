package com.geovault.tracker.runtime

import com.geovault.tracker.TrackingService
import com.geovault.tracker.services.TrackingRuntimeStateStore

internal interface RuntimeServiceStateProvider {
    fun isRuntimeStoreRunning(): Boolean
    fun isStartupInProgress(): Boolean
}

internal class RuntimeServiceStateProbe(
    private val provider: RuntimeServiceStateProvider = DefaultRuntimeServiceStateProvider
) {
    fun isServiceRunningOrStarting(): Boolean {
        return provider.isRuntimeStoreRunning() || provider.isStartupInProgress()
    }
}

private object DefaultRuntimeServiceStateProvider : RuntimeServiceStateProvider {
    override fun isRuntimeStoreRunning(): Boolean = TrackingRuntimeStateStore.state.value.isRunning

    override fun isStartupInProgress(): Boolean = TrackingService.isStartupInProgress
}
