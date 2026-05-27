package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation

private const val DEFAULT_AUTHORITATIVE_START_TOLERANCE_MS = 1_000L

/**
 * Ordered, contiguous run of points that all belong to the same recording session.
 * Segments are ordered ascending by [startTimestampMs] within a session list.
 */
data class TrackerSessionSegment(
    val startTimestampMs: Long,
    val points: List<QueuedLocation>,
)

/**
 * Optional context fed into [TrackerSessionAttributionPolicy.segment]. The
 * authoritative `currentSessionStartMs` is plumbed in only for the locally-recorded
 * tracker; for foreign trackers it must remain null because we do not know their
 * session boundaries definitively.
 */
data class TrackerSessionAttributionContext(
    /** Authoritative current-session start for the locally-recorded tracker; null for others. */
    val currentSessionStartMs: Long? = null,
    /**
     * Tolerance for reconciling server geometry session starts with the local runtime's
     * authoritative session start. Server API responses may round millisecond
     * starttimestamp values through seconds; only apply this when [currentSessionStartMs]
     * is supplied for the locally-recorded tracker.
     */
    val authoritativeStartToleranceMs: Long = DEFAULT_AUTHORITATIVE_START_TOLERANCE_MS,
)

/**
 * Pure attribution: turns a flat point list into ordered session segments.
 *
 * Boundary rules:
 *  - Distinct canonical `startTimestampMs` values present in the points (plus
 *    [TrackerSessionAttributionContext.currentSessionStartMs] when non-null) form the
 *    segment boundaries, sorted ascending.
 *  - A point with non-null `startTimestampMs` joins the segment with that exact start,
 *    except near-authoritative local starts can be canonicalized to
 *    [TrackerSessionAttributionContext.currentSessionStartMs].
 *  - A point with null `startTimestampMs` joins the segment whose start is the largest
 *    boundary `<= point.time`; if no such boundary exists it joins the earliest segment.
 *  - If no boundaries exist at all (no per-point starts, no authoritative override) all
 *    points collapse into a single synthetic segment whose start is the first point's
 *    time. The downstream filter still treats this as one session.
 *  - When `currentSessionStartMs` is provided, the corresponding segment is always
 *    materialized (possibly empty). This is what lets the "current_session" filter hide
 *    a previous session immediately after the user starts a new one, even before the
 *    first point of the new session arrives. The downstream filter's
 *    `_with_latest_point_fallback` then preserves the last attributable tail point so the marker
 *    has a position.
 *
 * Within each segment the input order is preserved.
 */
object TrackerSessionAttributionPolicy {
    fun segment(
        points: List<QueuedLocation>,
        context: TrackerSessionAttributionContext = TrackerSessionAttributionContext(),
    ): List<TrackerSessionSegment> {
        if (points.isEmpty() && context.currentSessionStartMs == null) return emptyList()
        if (points.isEmpty()) {
            return listOf(TrackerSessionSegment(startTimestampMs = context.currentSessionStartMs!!, points = emptyList()))
        }

        val boundaries = sortedBoundaries(points, context)
        if (boundaries.isEmpty()) {
            return listOf(TrackerSessionSegment(startTimestampMs = points.first().time, points = points))
        }

        val bucketsByStart = LinkedHashMap<Long, MutableList<QueuedLocation>>().apply {
            for (start in boundaries) put(start, mutableListOf())
        }
        for (point in points) {
            val targetStart = resolveTargetStart(point, boundaries, context)
            bucketsByStart.getValue(targetStart).add(point)
        }
        // Empty segments are preserved when they represent the authoritative current-session
        // boundary. Spurious empty buckets cannot otherwise occur because every boundary
        // either came from a point's startTimestampMs or from the authoritative override.
        return bucketsByStart.map { (start, members) ->
            TrackerSessionSegment(startTimestampMs = start, points = members.toList())
        }
    }

    private fun sortedBoundaries(points: List<QueuedLocation>, context: TrackerSessionAttributionContext): List<Long> {
        val starts = sortedSetOf<Long>()
        for (point in points) {
            point.startTimestampMs
                ?.let { canonicalizeStartTimestamp(it, context) }
                ?.let(starts::add)
        }
        context.currentSessionStartMs?.let(starts::add)
        return starts.toList()
    }

    /**
     * Find the segment a point belongs to. Caller guarantees [boundaries] is non-empty.
     */
    private fun resolveTargetStart(
        point: QueuedLocation,
        boundaries: List<Long>,
        context: TrackerSessionAttributionContext,
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
        context: TrackerSessionAttributionContext,
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
