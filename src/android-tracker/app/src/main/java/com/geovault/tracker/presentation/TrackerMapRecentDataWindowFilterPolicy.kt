package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation

/**
 * Client-side mirror of the server's `recent_data_window` filter
 * (see `src/backend/extensions/live_track/src/backend/helpers.py`).
 *
 * Applied as the last step of the render projection so live and queued
 * appends obey the same window the server uses for its initial response.
 *
 * Recognized keys:
 *  - "1min", "1h", "1d", "1w", "1m": rolling time window from `nowMs`.
 *  - "current_session": only the latest session by `startTimestampMs`.
 *  - "session": latest two sessions by `startTimestampMs`.
 *  - null / "all" / unknown: identity.
 *
 * If filtering would drop every point of an otherwise non-empty trail,
 * the most recent point is preserved so the marker still has a position
 * (matching the server's `_with_latest_point_fallback`).
 */
object TrackerMapRecentDataWindowFilterPolicy {

    private const val MS_PER_SEC = 1_000L
    private const val MS_PER_MIN = 60L * MS_PER_SEC
    private const val MS_PER_HOUR = 60L * MS_PER_MIN
    private const val MS_PER_DAY = 24L * MS_PER_HOUR
    private const val MS_PER_WEEK = 7L * MS_PER_DAY
    private const val MS_PER_MONTH = 30L * MS_PER_DAY

    private val ROLLING_WINDOWS_MS: Map<String, Long> = mapOf(
        "1min" to MS_PER_MIN,
        "1h" to MS_PER_HOUR,
        "1d" to MS_PER_DAY,
        "1w" to MS_PER_WEEK,
        "1m" to MS_PER_MONTH,
    )

    fun apply(
        points: List<QueuedLocation>,
        windowKey: String?,
        nowMs: Long,
    ): List<QueuedLocation> {
        if (points.isEmpty()) return points
        val key = windowKey?.trim()?.lowercase()
        if (key.isNullOrEmpty() || key == "all") return points
        return when (key) {
            "current_session" -> withLatestPointFallback(points, filterByLatestSessionStart(points))
            "session" -> withLatestPointFallback(points, filterByLastAndCurrentSession(points))
            else -> {
                val windowMs = ROLLING_WINDOWS_MS[key] ?: return points
                withLatestPointFallback(points, filterByRollingWindow(points, windowMs, nowMs))
            }
        }
    }

    private fun filterByRollingWindow(
        points: List<QueuedLocation>,
        windowMs: Long,
        nowMs: Long,
    ): List<QueuedLocation> {
        val cutoff = nowMs - windowMs
        return points.filter { it.time >= cutoff }
    }

    private fun filterByLatestSessionStart(points: List<QueuedLocation>): List<QueuedLocation> {
        val latestStart = points.mapNotNull { it.startTimestampMs }.maxOrNull() ?: return points
        return points.filter { point ->
            val start = point.startTimestampMs
            if (start != null) start == latestStart
            else point.time >= latestStart
        }
    }

    private fun filterByLastAndCurrentSession(points: List<QueuedLocation>): List<QueuedLocation> {
        val uniqueStartsDesc = points
            .mapNotNull { it.startTimestampMs }
            .distinct()
            .sortedDescending()
        if (uniqueStartsDesc.isEmpty()) return points
        val latestStart = uniqueStartsDesc[0]
        val previousStart = uniqueStartsDesc.getOrNull(1)
        val allowedStarts = if (previousStart != null) {
            setOf(latestStart, previousStart)
        } else {
            setOf(latestStart)
        }
        val fallbackCutoff = previousStart ?: latestStart
        return points.filter { point ->
            val start = point.startTimestampMs
            if (start != null) start in allowedStarts
            else point.time >= fallbackCutoff
        }
    }

    private fun withLatestPointFallback(
        original: List<QueuedLocation>,
        filtered: List<QueuedLocation>,
    ): List<QueuedLocation> {
        if (original.isNotEmpty() && filtered.isEmpty()) {
            return listOf(original.last())
        }
        return filtered
    }
}
