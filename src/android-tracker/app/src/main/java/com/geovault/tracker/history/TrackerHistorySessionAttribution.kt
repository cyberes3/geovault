package com.geovault.tracker.history

import com.geovault.tracker.db.QueuedLocation

private const val DEFAULT_AUTHORITATIVE_START_TOLERANCE_MS = 1_000L

data class TrackerHistorySessionSegment(
    val startTimestampMs: Long,
    val points: List<QueuedLocation>,
)

data class TrackerHistorySessionAttributionContext(
    val currentSessionStartMs: Long? = null,
    val authoritativeStartToleranceMs: Long = DEFAULT_AUTHORITATIVE_START_TOLERANCE_MS,
)

object TrackerHistorySessionAttribution {
    fun segment(
        points: List<QueuedLocation>,
        context: TrackerHistorySessionAttributionContext = TrackerHistorySessionAttributionContext(),
    ): List<TrackerHistorySessionSegment> {
        if (points.isEmpty() && context.currentSessionStartMs == null) return emptyList()
        if (points.isEmpty()) {
            return listOf(TrackerHistorySessionSegment(startTimestampMs = context.currentSessionStartMs!!, points = emptyList()))
        }

        val boundaries = sortedBoundaries(points, context)
        if (boundaries.isEmpty()) {
            return listOf(TrackerHistorySessionSegment(startTimestampMs = points.first().time, points = points))
        }

        val bucketsByStart = LinkedHashMap<Long, MutableList<QueuedLocation>>().apply {
            for (start in boundaries) put(start, mutableListOf())
        }
        for (point in points) {
            val targetStart = resolveTargetStart(point, boundaries, context)
            bucketsByStart.getValue(targetStart).add(point)
        }
        return bucketsByStart.map { (start, members) ->
            TrackerHistorySessionSegment(startTimestampMs = start, points = members.toList())
        }
    }

    private fun sortedBoundaries(points: List<QueuedLocation>, context: TrackerHistorySessionAttributionContext): List<Long> {
        val starts = sortedSetOf<Long>()
        for (point in points) {
            point.startTimestampMs
                ?.let { canonicalizeStartTimestamp(it, context) }
                ?.let(starts::add)
        }
        context.currentSessionStartMs?.let(starts::add)
        return starts.toList()
    }

    private fun resolveTargetStart(
        point: QueuedLocation,
        boundaries: List<Long>,
        context: TrackerHistorySessionAttributionContext,
    ): Long {
        val explicit = point.startTimestampMs
        if (explicit != null) return canonicalizeStartTimestamp(explicit, context)
        val time = point.time
        var attribution = boundaries.first()
        for (start in boundaries) {
            if (start <= time) attribution = start else break
        }
        return attribution
    }

    private fun canonicalizeStartTimestamp(
        startTimestampMs: Long,
        context: TrackerHistorySessionAttributionContext,
    ): Long {
        val authoritativeStart = context.currentSessionStartMs ?: return startTimestampMs
        val toleranceMs = context.authoritativeStartToleranceMs.coerceAtLeast(0L)
        return if (kotlin.math.abs(startTimestampMs - authoritativeStart) < toleranceMs) {
            authoritativeStart
        } else {
            startTimestampMs
        }
    }
}
