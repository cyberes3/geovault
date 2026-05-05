package com.geovault.tracker.runtime

import java.util.concurrent.atomic.AtomicReference

internal enum class TrackingServiceProcessState {
    Inactive,
    Starting,
    Usable,
    Destroying,
}

internal object TrackingServiceLifecycleGate {
    private val processState = AtomicReference(TrackingServiceProcessState.Inactive)

    fun markStarting() {
        processState.set(TrackingServiceProcessState.Starting)
    }

    fun markUsable() {
        processState.set(TrackingServiceProcessState.Usable)
    }

    fun markDestroying() {
        processState.set(TrackingServiceProcessState.Destroying)
    }

    fun markDestroyed() {
        processState.set(TrackingServiceProcessState.Inactive)
    }

    fun isServiceStartingOrUsable(): Boolean {
        return when (processState.get()) {
            TrackingServiceProcessState.Starting,
            TrackingServiceProcessState.Usable -> true
            TrackingServiceProcessState.Destroying,
            TrackingServiceProcessState.Inactive -> false
        }
    }

    fun resetForTests() {
        processState.set(TrackingServiceProcessState.Inactive)
    }
}
