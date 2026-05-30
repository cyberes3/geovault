package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation

/**
 * Recent-data-window selection on the map: drives the visible point subset for a
 * tracker's trail. Mirrors the server's `recent_data_window` selector
 * (`src/backend/extensions/live_track/src/backend/helpers.py`).
 *
 * Recognized keys:
 *  - `1min`, `1h`, `1d`, `1w`, `1m`: rolling time window from `nowMs`.
 *  - `current_session`: only the latest session.
 *  - `session`: latest two sessions (previous + current).
 *  - null / `all` / unknown: identity.
 *
 * Session-keyed selections delegate session attribution to
 * [TrackerSessionAttributionPolicy], which uses the authoritative current-session
 * start (when supplied) plus per-point starttimestamps to assign every point to
 * exactly one segment. The filter then keeps the last 1 (current_session) or last 2
 * (session) segments.
 *
 * Latest-point fallback parity with the server: if filtering would drop every point
 * of an otherwise non-empty trail, the most recent input point is preserved so the
 * marker still has a position.
 */
data class TrackerSessionWindowContext(
    val windowKey: String?,
    val nowMs: Long,
    val currentSessionStartMs: Long? = null,
)

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

    fun apply(points: List<QueuedLocation>, context: TrackerSessionWindowContext): List<QueuedLocation> {
        if (points.isEmpty()) return points
        val key = context.windowKey?.trim()?.lowercase()
        if (key.isNullOrEmpty() || key == "all") return points
        val result = when (key) {
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
        return result
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
        val segments = TrackerSessionAttributionPolicy.segment(
            points = points,
            context = TrackerSessionAttributionContext(currentSessionStartMs = currentSessionStartMs),
        )
        if (segments.isEmpty()) return emptyList()
        // Identity membership: a point's segment is the one the attributor placed it in,
        // and segments hold the original instances. IdentityHashMap keeps lookup O(1) and
        // sidesteps the data-class structural equality cost.
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
