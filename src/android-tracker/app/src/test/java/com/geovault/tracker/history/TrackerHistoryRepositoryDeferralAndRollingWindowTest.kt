package com.geovault.tracker.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerHistoryRepositoryDeferralAndRollingWindowTest {
    private fun trunkBatch(
        trackerId: String,
        window: TrackerHistoryWindow,
        times: List<Long>,
        startTimestampMs: Long? = null,
    ) = TrackerHistorySourceBatch(
        trackerId = trackerId,
        window = window,
        sourceKind = TrackerHistorySourceKind.FILTERED_SERVER_TRUNK,
        points = times.map { ts ->
            TrackerHistoryPoint(
                trackerId = trackerId,
                timestampMs = ts,
                latitude = 1.0,
                longitude = 2.0,
                provenance = TrackerHistoryProvenance.SERVER_GEOMETRY,
                startTimestampMs = startTimestampMs,
            )
        },
        complete = true,
    )

    /**
     * `current_session` is the one window whose empty-result path has no "keep the last point"
     * fallback (see `TrackerHistoryWindowFilter.filterCurrentSession`), so it is the realistic way
     * to reach a genuinely empty compose while a previous non-empty snapshot exists: a brand new
     * session starts (a fresh `activeSessionStartMs`) with zero points of its own yet, while the
     * *previous* session's trunk is still cached for this key.
     */
    @Test
    fun deferralWatchdog_forcesEmptyCommitAfterConsecutiveDeferrals() {
        val repository = TrackerHistoryRepository()
        val window = TrackerHistoryWindow("current_session")
        val key = TrackerHistoryKey("t1", window)
        val previousSessionStart = 0L
        val newSessionStart = 100_000L

        val initial = repository.commitTrunk(
            trunkBatch("t1", window, times = listOf(previousSessionStart), startTimestampMs = previousSessionStart),
            activeSessionStartMs = previousSessionStart,
            nowMs = previousSessionStart,
        )
        assertTrue(initial.committed)
        assertEquals(1, initial.snapshot.points.size)

        repeat(TrackerHistoryDeferralWatchdog.FORCE_COMMIT_AFTER) { attempt ->
            val result = repository.composeAndPublish(key, activeSessionStartMs = newSessionStart, nowMs = newSessionStart)
            assertFalse("attempt $attempt should defer, not commit", result.committed)
            assertEquals("empty_snapshot_deferred", result.reason)
            // Deferred: the repository's published snapshot must still be the stale-but-present one.
            assertEquals(1, repository.snapshotFor(key)!!.points.size)
        }

        val forced = repository.composeAndPublish(key, activeSessionStartMs = newSessionStart, nowMs = newSessionStart)
        assertTrue(forced.committed)
        assertEquals("forced_empty_commit", forced.reason)
        assertTrue(forced.snapshot.points.isEmpty())
        assertTrue(repository.snapshotFor(key)!!.points.isEmpty())
    }

    @Test
    fun deferralWatchdog_resetsAfterGenuineCommit() {
        val repository = TrackerHistoryRepository()
        val window = TrackerHistoryWindow("current_session")
        val key = TrackerHistoryKey("t1", window)
        val previousSessionStart = 0L
        val newSessionStart = 100_000L
        repository.commitTrunk(
            trunkBatch("t1", window, times = listOf(previousSessionStart), startTimestampMs = previousSessionStart),
            activeSessionStartMs = previousSessionStart,
            nowMs = previousSessionStart,
        )

        // Defer once against the still-empty new session, then a genuine point for that new
        // session arrives -- this should commit normally and reset the deferral count rather
        // than counting toward the force threshold.
        repository.composeAndPublish(key, activeSessionStartMs = newSessionStart, nowMs = newSessionStart)
        val revived = repository.commitTrunk(
            trunkBatch("t1", window, times = listOf(newSessionStart + 1_000L), startTimestampMs = newSessionStart),
            activeSessionStartMs = newSessionStart,
            nowMs = newSessionStart + 1_000L,
        )
        assertTrue(revived.committed)
        assertEquals("composed", revived.reason)

        // Deferring again afterward (a third session with no points yet) should require a fresh
        // full run of consecutive deferrals, not resume from where it left off before the
        // genuine commit.
        val thirdSessionStart = newSessionStart + 500_000L
        val deferredAgain = repository.composeAndPublish(key, activeSessionStartMs = thirdSessionStart, nowMs = thirdSessionStart)
        assertFalse(deferredAgain.committed)
        assertEquals("empty_snapshot_deferred", deferredAgain.reason)
    }

    @Test
    fun recomputeStaleRollingWindows_reExcludesPointsThatAgedOutWhileIdle() {
        val repository = TrackerHistoryRepository()
        val window = TrackerHistoryWindow("1h")
        val key = TrackerHistoryKey("t1", window)
        val oldPointMs = 0L
        val recentPointMs = 30 * 60_000L
        repository.commitTrunk(
            trunkBatch("t1", window, times = listOf(oldPointMs, recentPointMs)),
            activeSessionStartMs = null,
            nowMs = recentPointMs,
        )
        assertEquals(2, repository.snapshotFor(key)!!.points.size)

        // No new data arrives (idle) for another 40 minutes -- the older point is now outside the
        // 1h window. Without a periodic recompute nothing would ever re-trigger compose to notice.
        val changedKeys = repository.recomputeStaleRollingWindows(nowMs = recentPointMs + 40 * 60_000L)

        assertEquals(listOf(key), changedKeys)
        assertEquals(listOf(recentPointMs), repository.snapshotFor(key)!!.points.map { it.timestampMs })
    }

    @Test
    fun recomputeStaleRollingWindows_ignoresNonRollingWindowKeys() {
        val repository = TrackerHistoryRepository()
        val window = TrackerHistoryWindow("all")
        val key = TrackerHistoryKey("t1", window)
        repository.commitTrunk(trunkBatch("t1", window, times = listOf(0L)), activeSessionStartMs = null, nowMs = 0L)

        val changedKeys = repository.recomputeStaleRollingWindows(nowMs = 10 * 60_000L)

        assertTrue(changedKeys.isEmpty())
        assertEquals(1, repository.snapshotFor(key)!!.points.size)
    }

    @Test
    fun recomputeStaleRollingWindows_noOpWhenNothingChanged() {
        val repository = TrackerHistoryRepository()
        val window = TrackerHistoryWindow("1h")
        val key = TrackerHistoryKey("t1", window)
        repository.commitTrunk(trunkBatch("t1", window, times = listOf(0L)), activeSessionStartMs = null, nowMs = 0L)

        // Still well within the 1-hour window -- nothing should be dropped.
        val changedKeys = repository.recomputeStaleRollingWindows(nowMs = 60_000L)

        assertTrue(changedKeys.isEmpty())
        assertEquals(1, repository.snapshotFor(key)!!.points.size)
    }
}
