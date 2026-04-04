package com.geovault.tracker.runtime

import android.content.Context

internal class RuntimeStateRepository(
    context: Context,
    private val serviceStateProbe: RuntimeServiceStateProbe = RuntimeServiceStateProbe()
) : RuntimeStateAccessor {
    private val stateStore = RuntimeStateStore(context.applicationContext)

    override fun readState(): RuntimeState = stateStore.read()

    override fun updateState(transform: (RuntimeState) -> RuntimeState): RuntimeState {
        return stateStore.update(transform)
    }

    override fun isServiceRunning(): Boolean {
        return serviceStateProbe.isServiceRunningOrStarting()
    }
}
