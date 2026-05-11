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
        assertSame(points, apply(points, key = null))
    }

    @Test
    fun blankKey_isIdentity() {
        val points = listOf(point(time = 1L))
        assertSame(points, apply(points, key = "  "))
    }

    @Test
    fun allKey_isIdentity() {
        val points = listOf(point(time = 1L), point(time = 2L))
        assertSame(points, apply(points, key = "all"))
    }

    @Test
    fun unknownKey_isIdentity() {
        val points = listOf(point(time = 1L), point(time = 2L))
        assertSame(points, apply(points, key = "2h"))
    }

    @Test
    fun emptyTrail_returnsEmpty() {
        assertTrue(apply(emptyList(), key = "1h").isEmpty())
    }

    @Test
    fun rollingWindow_oneMinute_keepsRecentDropsOlder() {
        val points = listOf(
            point(time = NOW - 5 * 60_000L),
            point(time = NOW - 30_000L),
            point(time = NOW - 10_000L),
        )
        val filtered = apply(points, key = "1min")
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
        val filtered = apply(points, key = "1h")
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
        assertEquals(1, apply(points, key = "1d").size)
        assertEquals(2, apply(points, key = "1w").size)
        assertEquals(3, apply(points, key = "1m").size)
    }

    @Test
    fun rollingWindow_keepsLatestPoint_whenAllOutsideWindow() {
        val points = listOf(
            point(time = NOW - 10L * 60_000L),
            point(time = NOW - 5L * 60_000L),
        )
        val filtered = apply(points, key = "1min")
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
        val filtered = apply(points, key = "current_session")
        assertEquals(2, filtered.size)
        assertTrue(filtered.all { it.startTimestampMs == newer })
    }

    @Test
    fun currentSession_authoritativeStart_overridesPointsBoundaries() {
        val olderStart = 10_000L
        val authoritative = 50_000L
        val points = listOf(
            point(time = 100L, startTimestampMs = olderStart),
            point(time = 200L, startTimestampMs = olderStart),
            point(time = 60_000L, startTimestampMs = null),
        )
        val filtered = apply(points, key = "current_session", currentSessionStartMs = authoritative)
        assertEquals(listOf(60_000L), filtered.map { it.time })
    }

    @Test
    fun currentSession_missingStart_attributesByTimeBoundary() {
        val olderStart = 10_000L
        val latestStart = 20_000L
        val points = listOf(
            point(time = 5_000L, startTimestampMs = olderStart),
            point(time = 15_000L, startTimestampMs = null),
            point(time = 25_000L, startTimestampMs = null),
            point(time = 30_000L, startTimestampMs = latestStart),
        )
        val filtered = apply(points, key = "current_session")
        assertEquals(listOf(25_000L, 30_000L), filtered.map { it.time })
    }

    @Test
    fun currentSession_noStartTimestamps_returnsAll() {
        val points = listOf(
            point(time = 1L, startTimestampMs = null),
            point(time = 2L, startTimestampMs = null),
        )
        val filtered = apply(points, key = "current_session")
        assertEquals(points, filtered)
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
        val filtered = apply(points, key = "session")
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
        val filtered = apply(points, key = "session")
        assertEquals(2, filtered.size)
    }

    @Test
    fun session_missingStart_attributesByTimeBoundary() {
        val s1 = 1_000L
        val s2 = 2_000L
        val points = listOf(
            point(time = 500L, startTimestampMs = null),
            point(time = 1_500L, startTimestampMs = null),
            point(time = 2_500L, startTimestampMs = null),
            point(time = 100L, startTimestampMs = s1),
            point(time = 200L, startTimestampMs = s2),
        )
        val filtered = apply(points, key = "session")
        // With only two distinct sessions both segments are kept; null-start points
        // attribute to the latest segment whose start <= point.time, with the first
        // segment swallowing pre-boundary points (reasonable default when some rows omit session metadata).
        assertEquals(5, filtered.size)
        assertTrue(filtered.any { it.time == 500L })
        assertTrue(filtered.any { it.time == 1_500L })
        assertTrue(filtered.any { it.time == 2_500L })
        assertTrue(filtered.any { it.time == 100L })
        assertTrue(filtered.any { it.time == 200L })
    }

    @Test
    fun session_authoritativeStart_includesEvenWhenNoLivePointYet() {
        // The locally-recorded tracker just started a new session: trail still holds
        // last session's points (s_prev) and zero new points. The authoritative
        // current-session start nevertheless creates a "current" segment, and "session"
        // (last 2) keeps both prev and current segments.
        val sPrev = 1_000L
        val authoritative = 5_000L
        val points = listOf(
            point(time = 100L, startTimestampMs = sPrev),
            point(time = 200L, startTimestampMs = sPrev),
        )
        val filtered = apply(points, key = "session", currentSessionStartMs = authoritative)
        assertEquals(2, filtered.size)
        assertTrue(filtered.all { it.startTimestampMs == sPrev })
    }

    @Test
    fun session_afterLocalRestore_keepsPreviousNonEmptyAndCurrentSession() {
        val older = 1_000L
        val previous = 2_000L
        val current = 3_000L
        val points = listOf(
            point(time = 10L, startTimestampMs = older),
            point(time = 20L, startTimestampMs = older),
            point(time = 30L, startTimestampMs = previous),
            point(time = 40L, startTimestampMs = previous),
            point(time = 50L, startTimestampMs = current),
        )

        val filtered = apply(points, key = "session", currentSessionStartMs = current)

        assertEquals(listOf(30L, 40L, 50L), filtered.map { it.time })
        assertTrue(filtered.none { it.startTimestampMs == older })
    }

    @Test
    fun currentSession_keepsLatestPointWhenFilterWouldEmpty() {
        // The fallback is exercised when filtering nukes every point. Use authoritative
        // override that no point in the trail belongs to.
        val authoritative = 99_000L
        val points = listOf(
            point(time = 1L, startTimestampMs = 10_000L),
            point(time = 2L, startTimestampMs = 10_000L),
        )
        val filtered = apply(points, key = "current_session", currentSessionStartMs = authoritative)
        assertEquals(1, filtered.size)
        assertEquals(points.last(), filtered.single())
    }

    private fun apply(
        points: List<QueuedLocation>,
        key: String?,
        currentSessionStartMs: Long? = null,
    ): List<QueuedLocation> {
        return TrackerMapRecentDataWindowFilterPolicy.apply(
            points = points,
            context = TrackerSessionWindowContext(
                windowKey = key,
                nowMs = NOW,
                currentSessionStartMs = currentSessionStartMs,
            ),
        )
    }

    private fun point(
        time: Long,
        startTimestampMs: Long? = null,
    ): QueuedLocation {
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

    private companion object {
        const val NOW = 1_710_000_000_000L
    }
}
