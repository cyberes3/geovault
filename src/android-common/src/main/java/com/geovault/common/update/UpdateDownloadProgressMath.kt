package com.geovault.common.update

import kotlin.math.roundToLong

/**
 * Pure helpers for download ETA and rate display. Used by the update dialog and unit tests.
 */
object UpdateDownloadProgressMath {

    private const val MIN_BPS_FOR_ETA: Long = 1024L

    fun etaSecondsRemaining(bytesRemaining: Long, smoothedBytesPerSecond: Long): Long? {
        if (bytesRemaining <= 0L) return 0L
        if (smoothedBytesPerSecond < MIN_BPS_FOR_ETA) return null
        val seconds = (bytesRemaining.toDouble() / smoothedBytesPerSecond.toDouble()).roundToLong()
        return seconds.coerceAtLeast(1L)
    }

    fun exponentialMovingAverageBps(previous: Long?, instantBps: Long, alpha: Double): Long {
        if (previous == null || previous <= 0L) return instantBps.coerceAtLeast(0L)
        val p = previous.toDouble()
        val i = instantBps.toDouble().coerceAtLeast(0.0)
        return (alpha * i + (1.0 - alpha) * p).roundToLong().coerceAtLeast(0L)
    }
}
