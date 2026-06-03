package com.geovault.tracker.positioning.time

import android.os.SystemClock

interface PositioningClock {
    fun wallTimeMs(): Long
    fun elapsedRealtimeMs(): Long
    fun elapsedRealtimeNanos(): Long
}

object SystemPositioningClock : PositioningClock {
    override fun wallTimeMs(): Long = System.currentTimeMillis()

    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

    override fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()
}
