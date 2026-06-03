package com.geovault.tracker.replay

import com.geovault.tracker.location.AutoTrackingMotionEngine

object CaptureReplayTickInjector {
    const val TICK_INTERVAL_MS = 5_000L

    fun injectBetween(
        engine: AutoTrackingMotionEngine,
        fromWallMs: Long,
        toWallMs: Long,
        exclusiveEnd: Boolean = true,
    ) {
        var tickMs = fromWallMs + TICK_INTERVAL_MS
        val endMs = if (exclusiveEnd) toWallMs else toWallMs + TICK_INTERVAL_MS
        while (tickMs < endMs) {
            engine.onTick(nowMs = tickMs)
            tickMs += TICK_INTERVAL_MS
        }
    }
}
