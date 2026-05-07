package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapRecentDataWindowFilterPolicyTest {

    @Test
    fun nullKey_isIdentity() {
        val points = listOf(point(time = 1L), point(time = 2L))
        assertSame(points, TrackerMapRecentDataWindowFilterPolicy.apply(points, null, NOW))
    }

    @Test
    fun blankKey_isIdentity() {
        val points = listOf(point(time = 1L))
        assertSame(points, TrackerMapRecentDataWindowFilterPolicy.apply(points, "  ", NOW))
    }

    @Test
    fun allKey_isIdentity() {
        val points = listOf(point(time = 1L), point(time = 2L))
        assertSame(points, TrackerMapRecentDataWindowFilterPolicy.apply(points, "all", NOW))
    }

    @Test
    fun unknownKey_isIdentity() {
        val points = listOf(point(time = 1L), point(time = 2L))
        assertSame(points, TrackerMapRecentDataWindowFilterPolicy.apply(points, "2h", NOW))
    }

    @Test
    fun emptyTrail_returnsEmpty() {
        val out = TrackerMapRecentDataWindowFilterPolicy.apply(emptyList(), "1h", NOW)
        assertTrue(out.isEmpty())
    }

    @Test
    fun rollingWindow_oneMinute_keepsRecentDropsOlder() {
        val points = listOf(
            point(time = NOW - 5 * 60_000L),
            point(time = NOW - 30_000L),
            point(time = NOW - 10_000L),
        )
        val filtered = TrackerMapRecentDataWindowFilterPolicy.apply(points, "1min", NOW)
        assertEquals(2, filtered.size)
        assertEquals(NOW - 30_000L, filtered.first().time)
    }

    @Test
    fun rollingWindow_oneHour_keepsAtBoundary() {
        val cutoff = NOW - 3_600_000L
        val points = listOf(
            point(time = cutoff - 1L),
            point(time = cutoff),
            point(time = cutoff + 1L),
        )
        val filtered = TrackerMapRecentDataWindowFilterPolicy.apply(points, "1h", NOW)
        assertEquals(listOf(cutoff, cutoff + 1L), filtered.map { it.time })
    }

    @Test
    fun rollingWindow_oneDay_oneWeek_oneMonth_useExpectedCutoffs() {
        val day = NOW - 24L * 3_600_000L
        val week = NOW - 7L * 24L * 3_600_000L
        val month = NOW - 30L * 24L * 3_600_000L
        val points = listOf(
            point(time = month - 1L),
            point(time = week - 1L),
            point(time = day - 1L),
            point(time = NOW),
        )
        assertEquals(1, TrackerMapRecentDataWindowFilterPolicy.apply(points, "1d", NOW).size)
        assertEquals(2, TrackerMapRecentDataWindowFilterPolicy.apply(points, "1w", NOW).size)
        assertEquals(3, TrackerMapRecentDataWindowFilterPolicy.apply(points, "1m", NOW).size)
    }

    @Test
    fun rollingWindow_keepsLatestPoint_whenAllOutsideWindow() {
        val points = listOf(
            point(time = NOW - 10L * 60_000L),
            point(time = NOW - 5L * 60_000L),
        )
        val filtered = TrackerMapRecentDataWindowFilterPolicy.apply(points, "1min", NOW)
        assertEquals(1, filtered.size)
        assertEquals(points.last().time, filtered.single().time)
    }

    @Test
    fun currentSession_keepsOnlyLatestStartTimestamp() {
        val older = 10_000L
        val newer = 20_000L
        val points = listOf(
            point(time = 100L, startTimestampMs = older),
            point(time = 200L, startTimestampMs = older),
            point(time = 300L, startTimestampMs = newer),
            point(time = 400L, startTimestampMs = newer),
        )
        val filtered = TrackerMapRecentDataWindowFilterPolicy.apply(points, "current_session", NOW)
        assertEquals(2, filtered.size)
        assertTrue(filtered.all { it.startTimestampMs == newer })
    }

    @Test
    fun currentSession_missingStart_fallsBackToCoordTimestamp() {
        val latestStart = 20_000L
        val points = listOf(
            point(time = 5_000L, startTimestampMs = 10_000L),
            point(time = 15_000L, startTimestampMs = null),
            point(time = 25_000L, startTimestampMs = null),
            point(time = 30_000L, startTimestampMs = latestStart),
        )
        val filtered = TrackerMapRecentDataWindowFilterPolicy.apply(points, "current_session", NOW)
        assertEquals(listOf(25_000L, 30_000L), filtered.map { it.time })
    }

    @Test
    fun currentSession_noStartTimestamps_returnsAll() {
        val points = listOf(
            point(time = 1L, startTimestampMs = null),
            point(time = 2L, startTimestampMs = null),
        )
        assertSame(points, TrackerMapRecentDataWindowFilterPolicy.apply(points, "current_session", NOW))
    }

    @Test
    fun session_keepsLatestTwoStartTimestamps() {
        val s1 = 1_000L
        val s2 = 2_000L
        val s3 = 3_000L
        val points = listOf(
            point(time = 10L, startTimestampMs = s1),
            point(time = 20L, startTimestampMs = s2),
            point(time = 30L, startTimestampMs = s3),
            point(time = 40L, startTimestampMs = s2),
        )
        val filtered = TrackerMapRecentDataWindowFilterPolicy.apply(points, "session", NOW)
        assertEquals(3, filtered.size)
        assertTrue(filtered.none { it.startTimestampMs == s1 })
    }

    @Test
    fun session_singleSessionData_behavesLikeCurrentSession() {
        val onlyStart = 1_000L
        val points = listOf(
            point(time = 10L, startTimestampMs = onlyStart),
            point(time = 20L, startTimestampMs = onlyStart),
        )
        val filtered = TrackerMapRecentDataWindowFilterPolicy.apply(points, "session", NOW)
        assertEquals(2, filtered.size)
    }

    @Test
    fun session_missingStart_keepsPointsAfterPreviousBoundary() {
        val s1 = 1_000L
        val s2 = 2_000L
        val points = listOf(
            point(time = 500L, startTimestampMs = null),
            point(time = 1_500L, startTimestampMs = null),
            point(time = 2_500L, startTimestampMs = null),
            point(time = 100L, startTimestampMs = s1),
            point(time = 200L, startTimestampMs = s2),
        )
        val filtered = TrackerMapRecentDataWindowFilterPolicy.apply(points, "session", NOW)
        assertTrue(filtered.any { it.time == 1_500L })
        assertTrue(filtered.any { it.time == 2_500L })
        assertTrue(filtered.none { it.time == 500L })
        assertTrue(filtered.any { it.time == 100L })
        assertTrue(filtered.any { it.time == 200L })
    }

    @Test
    fun currentSession_keepsLatestPointWhenFilterWouldEmpty() {
        val points = listOf(
            point(time = 1L, startTimestampMs = 10_000L),
        )
        val filtered = TrackerMapRecentDataWindowFilterPolicy.apply(
            points = listOf(points.first()),
            windowKey = "current_session",
            nowMs = NOW,
        )
        assertEquals(1, filtered.size)
    }

    private fun point(
        time: Long,
        startTimestampMs: Long? = null,
    ): QueuedLocation {
        return QueuedLocation(
            id = 0L,
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

    private companion object {
        const val NOW = 1_710_000_000_000L
    }
}
