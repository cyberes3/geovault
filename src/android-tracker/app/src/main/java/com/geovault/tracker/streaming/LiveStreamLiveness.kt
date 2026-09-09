package com.geovault.tracker.streaming

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Sole app ping/pong clock. Points do not refresh this guard.
 */
internal class LiveStreamLiveness(
    private val scope: CoroutineScope,
    private val intervalMs: Long = StreamingConfig.livenessWatchdogIntervalMs,
    private val onTick: () -> Unit,
) {
    private var watchdogJob: Job? = null

    fun start() {
        if (watchdogJob != null) return
        watchdogJob = scope.launch {
            while (isActive) {
                delay(intervalMs)
                onTick()
            }
        }
    }

    fun stop() {
        watchdogJob?.cancel()
        watchdogJob = null
    }
}
