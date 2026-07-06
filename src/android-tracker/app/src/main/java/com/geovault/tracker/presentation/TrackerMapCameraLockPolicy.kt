package com.geovault.tracker.presentation

object TrackerMapCameraLockPolicy {
    fun shouldRenderUserLocation(runtimeRunning: Boolean): Boolean {
        return !runtimeRunning
    }
}
