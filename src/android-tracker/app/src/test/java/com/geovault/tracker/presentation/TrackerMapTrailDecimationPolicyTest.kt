package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapTrailDecimationPolicyTest {

    private fun point(
        time: Long,
        startTimestampMs: Long?,
        trackerId: String = "T",
        latitude: Double = 0.0,
        longitude: Double = 0.0,
    ) = QueuedLocation(
        id = time,
        trackerId = trackerId,
        time = time,
        latitude = latitude,
        longitude = longitude,
        altitude = null,
        speed = null,
        bearing = null,
        accuracy = null,
        sat = null,
        prov = null,
        dist = null,
        startTimestampMs = startTimestampMs,
    )

    @Test
    fun emptyInputReturnsEmpty() {
        assertEquals(emptyList<QueuedLocation>(), TrackerMapTrailDecimationPolicy.fitToCount(emptyList(), 10))
    }

    @Test
    fun targetZeroReturnsEmpty() {
        val pts = (1..3L).map { point(it, startTimestampMs = 100L) }
        assertEquals(emptyList<QueuedLocation>(), TrackerMapTrailDecimationPolicy.fitToCount(pts, 0))
    }

    @Test
    fun underTargetIsUnchanged() {
        val pts = listOf(
            point(1, startTimestampMs = 100L),
            point(2, startTimestampMs = 100L),
        )
        assertEquals(pts, TrackerMapTrailDecimationPolicy.fitToCount(pts, 4000))
    }

    @Test
    fun singleSessionOverTargetUsesTakeLast() {
        val pts = (1..10L).map { point(it, startTimestampMs = 100L) }
        val out = TrackerMapTrailDecimationPolicy.fitToCount(pts, 5)
        assertEquals(5, out.size)
        // Single-session fast path mirrors `takeLast` to avoid surprise re-decimation.
        assertEquals(pts.takeLast(5), out)
    }

    @Test
    fun twoEqualSessions_eachKeepsAnchors() {
        // Session A: 6 points, Session B: 6 points. Cap at 6 total -> both sessions keep 3 each.
        val a = (1..6L).map { point(it, startTimestampMs = 100L) }
        val b = (10..15L).map { point(it, startTimestampMs = 1000L) }
        val out = TrackerMapTrailDecimationPolicy.fitToCount(a + b, 6)
        assertTrue("size should be <= target, got ${out.size}", out.size <= 6)
        assertTrue("keeps A first", out.contains(a.first()))
        assertTrue("keeps A last", out.contains(a.last()))
        assertTrue("keeps B first", out.contains(b.first()))
        assertTrue("keeps B last", out.contains(b.last()))
    }

    @Test
    fun twoSessions_overTarget_resultIsAtMostTarget() {
        val a = (1..50L).map { point(it, startTimestampMs = 100L) }
        val b = (60..70L).map { point(it, startTimestampMs = 1000L) }
        val out = TrackerMapTrailDecimationPolicy.fitToCount(a + b, 8)
        assertTrue("size should be <= target, got ${out.size}", out.size <= 8)
        assertTrue("keeps A first", out.contains(a.first()))
        assertTrue("keeps A last", out.contains(a.last()))
        assertTrue("keeps B first", out.contains(b.first()))
        assertTrue("keeps B last", out.contains(b.last()))
    }

    @Test
    fun unequalSessions_largerOneAbsorbsMostExtras() {
        // Big session of 100 points, small session of 5. Cap at 50: we expect the big session
        // to receive far more allocation than the small one (proportional to size beyond floor),
        // but the small session always retains both anchors.
        val big = (1..100L).map { point(it, startTimestampMs = 100L) }
        val small = (200..204L).map { point(it, startTimestampMs = 1000L) }
        val out = TrackerMapTrailDecimationPolicy.fitToCount(big + small, 50)
        val fromBig = out.count { it.startTimestampMs == 100L }
        val fromSmall = out.count { it.startTimestampMs == 1000L }
        assertTrue("size <= 50 (got ${out.size})", out.size <= 50)
        assertTrue("big session keeps the bulk (got fromBig=$fromBig)", fromBig > fromSmall * 3)
        assertTrue(out.contains(small.first()))
        assertTrue(out.contains(small.last()))
    }

    @Test
    fun outputIsStableAndChronological() {
        // Even when decimating, the output should preserve the input order (chronologically
        // ascending here) — downstream renderers rely on this.
        val a = (1..40L).map { point(it, startTimestampMs = 100L) }
        val b = (60..90L).map { point(it, startTimestampMs = 1000L) }
        val out = TrackerMapTrailDecimationPolicy.fitToCount(a + b, 30)
        val times = out.map { it.time }
        assertEquals(times.sorted(), times)
    }
}
