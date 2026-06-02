package com.geovault.tracker.history

import com.geovault.tracker.db.QueuedLocation

data class TrackerHistoryWindowContext(
    val windowKey: String?,
    val nowMs: Long,
    val currentSessionStartMs: Long? = null,
)

object TrackerHistoryWindowFilter {
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

    fun apply(points: List<QueuedLocation>, context: TrackerHistoryWindowContext): List<QueuedLocation> {
        if (points.isEmpty()) return points
        val key = context.windowKey?.trim()?.lowercase()
        if (key.isNullOrEmpty() || key == "all") return points
        return when (key) {
            "current_session" -> filterCurrentSession(points, context.currentSessionStartMs)
            "session" -> withLatestPointFallback(
                original = points,
                filtered = filterByLatestSegments(points, context.currentSessionStartMs, keep = 2),
            )
            else -> {
                val windowMs = ROLLING_WINDOWS_MS[key] ?: return points
                withLatestPointFallback(
                    original = points,
                    filtered = filterByRollingWindow(points, windowMs, context.nowMs),
                )
            }
        }
    }

    private fun filterCurrentSession(
        points: List<QueuedLocation>,
        currentSessionStartMs: Long?,
    ): List<QueuedLocation> {
        val filtered = filterByLatestSegments(points, currentSessionStartMs, keep = 1)
        if (currentSessionStartMs != null) return filtered
        return withLatestPointFallback(original = points, filtered = filtered)
    }

    private fun filterByLatestSegments(
        points: List<QueuedLocation>,
        currentSessionStartMs: Long?,
        keep: Int,
    ): List<QueuedLocation> {
        val segments = TrackerHistorySessionAttribution.segment(
            points = points,
            context = TrackerHistorySessionAttributionContext(currentSessionStartMs = currentSessionStartMs),
        )
        if (segments.isEmpty()) return emptyList()
        val keptIdentity = java.util.IdentityHashMap<QueuedLocation, Boolean>()
        for (segment in segments.takeLast(keep)) {
            for (point in segment.points) keptIdentity[point] = true
        }
        return points.filter { keptIdentity.containsKey(it) }
    }

    private fun filterByRollingWindow(
        points: List<QueuedLocation>,
        windowMs: Long,
        nowMs: Long,
    ): List<QueuedLocation> {
        val cutoff = nowMs - windowMs
        return points.filter { it.time >= cutoff }
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
