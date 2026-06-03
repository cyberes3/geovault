package com.geovault.tracker.replay.runtime

import com.geovault.tracker.positioning.time.PositioningClock

class ReplayPositioningClock(
    wallTimeMs: Long,
    elapsedRealtimeNanos: Long,
) : PositioningClock {
    private var wallTimeMsValue: Long = wallTimeMs
    private var elapsedRealtimeNanosValue: Long = elapsedRealtimeNanos

    override fun wallTimeMs(): Long = wallTimeMsValue

    override fun elapsedRealtimeMs(): Long = elapsedRealtimeNanosValue / 1_000_000L

    override fun elapsedRealtimeNanos(): Long = elapsedRealtimeNanosValue

    fun advanceTo(wallTimeMs: Long, elapsedRealtimeNanos: Long) {
        require(wallTimeMs >= wallTimeMsValue) { "replay wall clock cannot move backwards" }
        require(elapsedRealtimeNanos >= elapsedRealtimeNanosValue) {
            "replay elapsedRealtime cannot move backwards"
        }
        wallTimeMsValue = wallTimeMs
        elapsedRealtimeNanosValue = elapsedRealtimeNanos
    }
}
