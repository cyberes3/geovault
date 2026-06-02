package com.geovault.tracker.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerHistoryActiveSessionPolicyTest {

    @Test
    fun prepareTrunkForCommit_emptyTrunk_rejects() {
        val batch = TrackerHistorySourceBatch(
            trackerId = "tracker-1",
            window = TrackerHistoryWindow("current_session"),
            sourceKind = TrackerHistorySourceKind.FILTERED_SERVER_TRUNK,
            points = emptyList(),
        )

        val result = TrackerHistoryActiveSessionPolicy.prepareTrunkForCommit(
            batch = batch,
            activeSessionStartMs = 10_000L,
        )

        assertTrue(result is TrackerHistoryTrunkPrepareResult.Reject)
        assertEquals("empty_trunk", (result as TrackerHistoryTrunkPrepareResult.Reject).reason)
    }

    @Test
    fun prepareTrunkForCommit_staleTrunkBeforeActiveSession_rejects() {
        val batch = TrackerHistorySourceBatch(
            trackerId = "tracker-1",
            window = TrackerHistoryWindow("current_session"),
            sourceKind = TrackerHistorySourceKind.FILTERED_SERVER_TRUNK,
            points = listOf(
                point(time = 1_000L, sessionStart = 1_000L),
                point(time = 2_000L, sessionStart = 1_000L),
            ),
        )

        val result = TrackerHistoryActiveSessionPolicy.prepareTrunkForCommit(
            batch = batch,
            activeSessionStartMs = 50_000L,
        )

        assertTrue(result is TrackerHistoryTrunkPrepareResult.Reject)
        assertEquals(
            "stale_trunk_before_active_session",
            (result as TrackerHistoryTrunkPrepareResult.Reject).reason,
        )
    }

    @Test
    fun prepareTrunkForCommit_clipsToActiveSession() {
        val activeStart = 10_000L
        val batch = TrackerHistorySourceBatch(
            trackerId = "tracker-1",
            window = TrackerHistoryWindow("current_session"),
            sourceKind = TrackerHistorySourceKind.FILTERED_SERVER_TRUNK,
            points = listOf(
                point(time = 5_000L, sessionStart = 1_000L),
                point(time = 11_000L, sessionStart = activeStart),
                point(time = 12_000L, sessionStart = activeStart),
            ),
        )

        val result = TrackerHistoryActiveSessionPolicy.prepareTrunkForCommit(
            batch = batch,
            activeSessionStartMs = activeStart,
        )

        assertTrue(result is TrackerHistoryTrunkPrepareResult.Commit)
        val commit = result as TrackerHistoryTrunkPrepareResult.Commit
        assertTrue(commit.clipped)
        assertEquals(listOf(11_000L, 12_000L), commit.batch.points.map { it.timestampMs })
    }

    @Test
    fun prepareTrunkForCommit_nonSessionWindow_commitsWithoutClip() {
        val batch = TrackerHistorySourceBatch(
            trackerId = "tracker-1",
            window = TrackerHistoryWindow("1h"),
            sourceKind = TrackerHistorySourceKind.FILTERED_SERVER_TRUNK,
            points = listOf(point(time = 1_000L, sessionStart = 1_000L)),
        )

        val result = TrackerHistoryActiveSessionPolicy.prepareTrunkForCommit(
            batch = batch,
            activeSessionStartMs = 50_000L,
        )

        assertTrue(result is TrackerHistoryTrunkPrepareResult.Commit)
        val commit = result as TrackerHistoryTrunkPrepareResult.Commit
        assertEquals(false, commit.clipped)
        assertEquals(1, commit.batch.points.size)
    }

    private fun point(time: Long, sessionStart: Long?): TrackerHistoryPoint {
        return TrackerHistoryPoint(
            trackerId = "tracker-1",
            timestampMs = time,
            latitude = 35.0,
            longitude = -106.0,
            startTimestampMs = sessionStart,
            provenance = TrackerHistoryProvenance.SERVER_GEOMETRY,
        )
    }
}
