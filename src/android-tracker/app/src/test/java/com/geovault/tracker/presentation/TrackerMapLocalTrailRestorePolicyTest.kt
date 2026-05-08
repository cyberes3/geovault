package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapLocalTrailRestorePolicyTest {

    @Test
    fun restore_historyPlusLivePoint_keepsPreviousAndCurrentSessionsChronological() {
        val previousStart = 1_000L
        val currentStart = 2_000L
        val history = listOf(
            point(id = 1L, time = 10L, startTimestampMs = previousStart),
            point(id = 2L, time = 20L, startTimestampMs = previousStart),
            point(id = 3L, time = 30L, startTimestampMs = currentStart),
        )
        val livePoint = point(
            id = 0L,
            time = 40L,
            provenance = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS_RUNTIME,
            startTimestampMs = currentStart,
        )

        val result = TrackerMapLocalTrailRestorePolicy.restore(
            localHistoryTrail = history,
            currentTrail = listOf(livePoint),
            trackerId = TRACKER_ID,
            trailPointLimit = 100,
        )

        assertTrue(result.changed)
        assertEquals(listOf(10L, 20L, 30L, 40L), result.trail.map { it.time })
        assertEquals(listOf(previousStart, previousStart, currentStart, currentStart), result.trail.map { it.startTimestampMs })
    }

    @Test
    fun restore_duplicatePersistedAndLivePoint_deduplicatesByIdAndPointKey() {
        val persisted = point(id = 7L, time = 10L)
        val livePoint = point(
            id = 0L,
            time = 20L,
            provenance = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS_RUNTIME,
        )
        val duplicateLivePoint = livePoint.copy(id = 0L)

        val result = TrackerMapLocalTrailRestorePolicy.restore(
            localHistoryTrail = listOf(persisted, persisted.copy()),
            currentTrail = listOf(livePoint, duplicateLivePoint),
            trackerId = TRACKER_ID,
            trailPointLimit = 100,
        )

        assertEquals(listOf(10L, 20L), result.trail.map { it.time })
    }

    @Test
    fun restore_emptyHistory_isNoOp() {
        val current = listOf(point(id = 0L, time = 20L))

        val result = TrackerMapLocalTrailRestorePolicy.restore(
            localHistoryTrail = emptyList(),
            currentTrail = current,
            trackerId = TRACKER_ID,
            trailPointLimit = 100,
        )

        assertFalse(result.changed)
        assertEquals(current, result.trail)
    }

    private fun point(
        id: Long,
        time: Long,
        trackerId: String = TRACKER_ID,
        provenance: String = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS,
        startTimestampMs: Long? = null,
    ): QueuedLocation {
        return QueuedLocation(
            id = id,
            trackerId = trackerId,
            time = time,
            latitude = time.toDouble() / 1000.0,
            longitude = time.toDouble() / 1000.0,
            altitude = null,
            speed = null,
            bearing = null,
            accuracy = null,
            sat = null,
            prov = provenance,
            dist = null,
            startTimestampMs = startTimestampMs,
        )
    }

    private companion object {
        const val TRACKER_ID = "tracker-1"
    }
}
