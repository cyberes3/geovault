package com.geovault.tracker.runtime

import android.content.Context
import com.geovault.tracker.services.TrackingRuntimeStateStore

class RuntimeStateRepository(context: Context) {
    private val stateStore = RuntimeStateStore(context.applicationContext)

    fun readState(): RuntimeState = stateStore.read()

    fun updateState(transform: (RuntimeState) -> RuntimeState): RuntimeState {
        return stateStore.update(transform)
    }

    fun isServiceRunning(): Boolean {
        return TrackingRuntimeStateStore.state.value.isRunning
    }
}
