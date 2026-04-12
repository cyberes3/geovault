package com.geovault.tracker.runtime

interface RuntimeStateAccessor {
    fun readState(): RuntimeState

    fun updateState(transform: (RuntimeState) -> RuntimeState): RuntimeState

    fun isServiceRunning(): Boolean
}
