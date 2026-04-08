package com.geovault.tracker.presentation

import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks in-flight `/geometry/` requests and emits loading state transitions.
 */
class TrackerMapGeometryLoadingTracker(
    private val onLoadingChanged: (Boolean) -> Unit,
) {
    private val activeRequestCount = AtomicInteger(0)

    suspend fun <T> track(block: suspend () -> T): T {
        beginRequest()
        return try {
            block()
        } finally {
            endRequest()
        }
    }

    private fun beginRequest() {
        val previous = activeRequestCount.getAndIncrement()
        if (previous == 0) {
            onLoadingChanged(true)
        }
    }

    private fun endRequest() {
        val next = activeRequestCount.decrementAndGet()
        if (next <= 0) {
            // Defensive clamp against accidental imbalance.
            activeRequestCount.set(0)
            onLoadingChanged(false)
        }
    }
}
