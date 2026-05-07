package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerSessionAttributionPolicyTest {

    @Test
    fun emptyPoints_noAuthoritative_yieldsEmpty() {
        val out = TrackerSessionAttributionPolicy.segment(emptyList())
        assertTrue(out.isEmpty())
    }

    @Test
    fun emptyPoints_authoritative_yieldsSingleEmptySegment() {
        val out = TrackerSessionAttributionPolicy.segment(
            points = emptyList(),
            context = TrackerSessionAttributionContext(currentSessionStartMs = 5_000L),
        )
        assertEquals(1, out.size)
        assertEquals(5_000L, out.single().startTimestampMs)
        assertTrue(out.single().points.isEmpty())
    }

    @Test
    fun singlePoint_synthesizesSegmentFromTime() {
        val p = point(time = 500L)
        val out = TrackerSessionAttributionPolicy.segment(listOf(p))
        assertEquals(1, out.size)
        assertEquals(500L, out.single().startTimestampMs)
        assertSame(p, out.single().points.single())
    }

    @Test
    fun pointsWithMatchingStarts_groupByStart() {
        val s1 = 1_000L
        val s2 = 2_000L
        val p1 = point(time = 10L, startTimestampMs = s1)
        val p2 = point(time = 20L, startTimestampMs = s2)
        val p3 = point(time = 30L, startTimestampMs = s1)
        val out = TrackerSessionAttributionPolicy.segment(listOf(p1, p2, p3))
        assertEquals(listOf(s1, s2), out.map { it.startTimestampMs })
        assertEquals(listOf(p1, p3), out[0].points)
        assertEquals(listOf(p2), out[1].points)
    }

    @Test
    fun nullStartPoint_attachesToLatestBoundaryAtOrBeforeItsTime() {
        val s1 = 1_000L
        val s2 = 5_000L
        val pre = point(time = 500L)
        val between = point(time = 3_000L)
        val after = point(time = 7_000L)
        val anchorS1 = point(time = 1_500L, startTimestampMs = s1)
        val anchorS2 = point(time = 5_500L, startTimestampMs = s2)
        val out = TrackerSessionAttributionPolicy.segment(listOf(pre, between, after, anchorS1, anchorS2))
        val s1Segment = out.first { it.startTimestampMs == s1 }
        val s2Segment = out.first { it.startTimestampMs == s2 }
        assertTrue(pre in s1Segment.points)
        assertTrue(between in s1Segment.points)
        assertTrue(anchorS1 in s1Segment.points)
        assertTrue(after in s2Segment.points)
        assertTrue(anchorS2 in s2Segment.points)
    }

    @Test
    fun authoritativeStart_materializesEvenWithoutMatchingPoint() {
        val sPrev = 1_000L
        val authoritative = 5_000L
        val pPrev = point(time = 100L, startTimestampMs = sPrev)
        val out = TrackerSessionAttributionPolicy.segment(
            points = listOf(pPrev),
            context = TrackerSessionAttributionContext(currentSessionStartMs = authoritative),
        )
        assertEquals(listOf(sPrev, authoritative), out.map { it.startTimestampMs })
        assertEquals(listOf(pPrev), out[0].points)
        assertTrue(out[1].points.isEmpty())
    }

    @Test
    fun authoritativeStart_canCombineWithMatchingPointStart() {
        // When a point already carries the same startTimestampMs as the authoritative
        // override, only one segment exists at that boundary.
        val authoritative = 5_000L
        val current = point(time = 5_500L, startTimestampMs = authoritative)
        val out = TrackerSessionAttributionPolicy.segment(
            points = listOf(current),
            context = TrackerSessionAttributionContext(currentSessionStartMs = authoritative),
        )
        assertEquals(listOf(authoritative), out.map { it.startTimestampMs })
        assertEquals(listOf(current), out.single().points)
    }

    @Test
    fun nullStarts_collapseIntoSyntheticSegment_whenNoBoundariesExist() {
        val p1 = point(time = 100L)
        val p2 = point(time = 200L)
        val p3 = point(time = 50L)
        val out = TrackerSessionAttributionPolicy.segment(listOf(p1, p2, p3))
        assertEquals(1, out.size)
        assertEquals(p1.time, out.single().startTimestampMs)
        assertEquals(listOf(p1, p2, p3), out.single().points)
    }

    @Test
    fun preBoundaryNullStartPoint_fallsIntoEarliestSegment() {
        val s1 = 1_000L
        val s2 = 2_000L
        val pBefore = point(time = 500L)
        val pAfterS1 = point(time = 1_500L, startTimestampMs = s1)
        val pAfterS2 = point(time = 2_500L, startTimestampMs = s2)
        val out = TrackerSessionAttributionPolicy.segment(listOf(pBefore, pAfterS1, pAfterS2))
        assertTrue(pBefore in out.first { it.startTimestampMs == s1 }.points)
    }

    @Test
    fun segmentsAreOrderedAscending() {
        val p1 = point(time = 10L, startTimestampMs = 3_000L)
        val p2 = point(time = 20L, startTimestampMs = 1_000L)
        val p3 = point(time = 30L, startTimestampMs = 2_000L)
        val out = TrackerSessionAttributionPolicy.segment(listOf(p1, p2, p3))
        assertEquals(listOf(1_000L, 2_000L, 3_000L), out.map { it.startTimestampMs })
    }

    @Test
    fun inputOrderPreservedWithinSegment() {
        val s1 = 1_000L
        val a = point(time = 30L, startTimestampMs = s1)
        val b = point(time = 10L, startTimestampMs = s1)
        val c = point(time = 20L, startTimestampMs = s1)
        val out = TrackerSessionAttributionPolicy.segment(listOf(a, b, c))
        assertEquals(listOf(a, b, c), out.single().points)
    }

    private fun point(time: Long, startTimestampMs: Long? = null): QueuedLocation {
        return QueuedLocation(
            id = idCounter++,
            trackerId = "tracker-1",
            time = time,
            latitude = 0.0,
            longitude = 0.0,
            altitude = null,
            speed = null,
            bearing = null,
            accuracy = null,
            sat = null,
            prov = null,
            dist = null,
            startTimestampMs = startTimestampMs,
        )
    }

    private var idCounter: Long = 1L
}
