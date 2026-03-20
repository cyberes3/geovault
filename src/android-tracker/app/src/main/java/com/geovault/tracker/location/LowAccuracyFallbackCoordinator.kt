package com.geovault.tracker.location

internal class LowAccuracyFallbackCoordinator {
    private var awaitingLock: Boolean = false

    fun onRejectedFixForLock(fallbackEligible: Boolean): Boolean {
        if (!fallbackEligible) return false
        val shouldStartTimer = !awaitingLock
        awaitingLock = true
        return shouldStartTimer
    }

    fun onAcceptedFix() {
        awaitingLock = false
    }

    fun onTrackingStopped() {
        awaitingLock = false
    }

    fun shouldEmitFallback(fallbackEligible: Boolean, hasCandidate: Boolean): Boolean {
        if (!fallbackEligible) return false
        if (!hasCandidate) return false
        return awaitingLock
    }
}
