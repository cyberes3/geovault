package com.geovault.tracker.presentation

object TrackerMapCameraLockPolicy {
    fun shouldRenderUserLocation(runtimeRunning: Boolean): Boolean {
        return !runtimeRunning
    }

    fun shouldEnableFollowCamera(runtimeRunning: Boolean, followLockEnabled: Boolean): Boolean {
        return !runtimeRunning && followLockEnabled
    }
}
