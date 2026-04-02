package com.geovault.tracker.runtime

import android.content.Context
import com.geovault.tracker.services.TrackingRuntimeStateStore

class RuntimeStateRepository(context: Context) : RuntimeStateAccessor {
    private val stateStore = RuntimeStateStore(context.applicationContext)

    override fun readState(): RuntimeState = stateStore.read()

    override fun updateState(transform: (RuntimeState) -> RuntimeState): RuntimeState {
        return stateStore.update(transform)
    }

    override fun isServiceRunning(): Boolean {
        return TrackingRuntimeStateStore.state.value.isRunning
    }
}
