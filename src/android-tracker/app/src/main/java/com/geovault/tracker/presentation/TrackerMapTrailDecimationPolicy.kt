package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import java.util.TreeSet
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Bound a trail's point count without losing whole sessions.
 *
 * The naive `takeLast(TRAIL_POINT_LIMIT)` cap drops the OLDEST points first, which
 * silently violates user-facing recent-data-window filters that promise N sessions
 * (e.g. `recent_data_window = "session"` keeps the previous + current sessions). Once
 * the combined point count of those sessions exceeds the trail cap, the previous
 * session disappears entirely.
 *
 * This policy decimates each session segment uniformly (always keeping the first and
 * last point of every segment) so the cap never erases a full session. The algorithm
 * mirrors the backend `_fit_session_aware_indices` in
 * `src/backend/extensions/live_track/src/backend/tracker_views.py` so the client and
 * server agree on which points to keep.
 */
object TrackerMapTrailDecimationPolicy {

    fun fitToCount(points: List<QueuedLocation>, target: Int): List<QueuedLocation> {
        if (target <= 0) return emptyList()
        if (points.size <= target) return points
        val segments = TrackerSessionAttributionPolicy.segment(
            points = points,
            context = TrackerSessionAttributionContext(),
        )
        if (segments.isEmpty()) return points
        if (segments.size == 1) return points.takeLast(target)

        val segmentIndices: List<List<Int>> = buildSegmentIndices(points, segments)
        val seenAttributable = segmentIndices.sumOf { it.size }
        if (seenAttributable == 0) return points.takeLast(target)

        val seenLens = segmentIndices.map { it.size }
        val floors = seenLens.map { min(2, it) }
        val floorSum = floors.sum()
        val totalAttributable = seenAttributable

        val effectiveTarget = target.coerceAtLeast(floorSum).coerceAtMost(totalAttributable)
        val extrasPool = totalAttributable - floorSum
        val remainingExtras = effectiveTarget - floorSum

        val allocations = IntArray(segmentIndices.size) { segIdx ->
            val length = seenLens[segIdx]
            val floor = floors[segIdx]
            val extraAvail = length - floor
            if (extrasPool == 0 || remainingExtras <= 0) {
                floor
            } else {
                val extra = ((remainingExtras.toDouble() * extraAvail) / extrasPool).roundToInt()
                floor + extra.coerceIn(0, extraAvail)
            }
        }

        // Rounding can push the sum above the target by up to (segments - 1). Trim the
        // overshoot from the largest segment first so the boundary anchors stay intact.
        var overshoot = allocations.sum() - effectiveTarget
        while (overshoot > 0) {
            var bestIdx = -1
            var bestRoom = 0
            for (i in allocations.indices) {
                val room = allocations[i] - floors[i]
                if (room > bestRoom) {
                    bestRoom = room
                    bestIdx = i
                }
            }
            if (bestIdx < 0) break
            allocations[bestIdx] -= 1
            overshoot -= 1
        }

        val kept = TreeSet<Int>()
        for ((segIdx, indices) in segmentIndices.withIndex()) {
            for (idx in uniformStrideKeep(indices, allocations[segIdx])) {
                kept.add(idx)
            }
        }
        return points.filterIndexed { index, _ -> index in kept }
    }

    /**
     * Identity-mapping of the segmented points back to original input indices. Built in
     * O(n) with an [java.util.IdentityHashMap] so equal-but-distinct point instances
     * (synthetic timestamps etc.) cannot collide.
     */
    private fun buildSegmentIndices(
        points: List<QueuedLocation>,
        segments: List<TrackerSessionSegment>,
    ): List<List<Int>> {
        val identityIndex = java.util.IdentityHashMap<QueuedLocation, Int>(points.size)
        for ((idx, point) in points.withIndex()) {
            identityIndex[point] = idx
        }
        return segments.map { segment ->
            segment.points.mapNotNull { identityIndex[it] }
        }
    }

    private fun uniformStrideKeep(indices: List<Int>, targetCount: Int): List<Int> {
        val n = indices.size
        if (n <= 0 || targetCount >= n) return indices
        if (targetCount <= 1) return listOf(indices.last())
        if (targetCount == 2) return listOf(indices.first(), indices.last())
        val step = (n - 1).toDouble() / (targetCount - 1).toDouble()
        val seen = LinkedHashSet<Int>(targetCount)
        for (k in 0 until targetCount) {
            val pos = (k * step).roundToInt().coerceIn(0, n - 1)
            seen.add(indices[pos])
        }
        seen.add(indices.last())
        return seen.sorted()
    }
}
