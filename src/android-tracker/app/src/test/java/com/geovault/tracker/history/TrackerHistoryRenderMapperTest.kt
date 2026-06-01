package com.geovault.tracker.history

import com.geovault.tracker.db.QueuedLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerHistoryRenderMapperTest {
    @Test
    fun toQueuedLocations_appliesDecimationAtRenderBoundary() {
        val points = (1..100).map { index ->
            TrackerHistoryPoint(
                trackerId = "t1",
                timestampMs = index.toLong(),
                latitude = index.toDouble(),
                longitude = index.toDouble(),
                startTimestampMs = 1L,
                provenance = TrackerHistoryProvenance.SERVER_GEOMETRY,
            )
        }
        val snapshot = TrackerHistorySnapshot(
            key = TrackerHistoryKey("t1", TrackerHistoryWindow("all")),
            trunk = points,
            overlay = emptyList(),
            points = points,
            committedAtMs = 1L,
            generation = 1L,
            complete = true,
        )

        val rendered = TrackerHistoryRenderMapper.toQueuedLocations(snapshot, trailPointLimit = 4)

        assertTrue(rendered.isNotEmpty())
        assertTrue(rendered.size <= 4)
        assertEquals(100L, rendered.last().time)
    }

    @Test
    fun trailsByTracker_readsPerTrackerWindowSnapshot() {
        val window = TrackerHistoryWindow("1h")
        val snapshot = TrackerHistorySnapshot(
            key = TrackerHistoryKey("t1", window),
            trunk = listOf(
                TrackerHistoryPoint(
                    trackerId = "t1",
                    timestampMs = 5L,
                    latitude = 1.0,
                    longitude = 2.0,
                    provenance = TrackerHistoryProvenance.SERVER_GEOMETRY,
                ),
            ),
            overlay = emptyList(),
            points = listOf(
                TrackerHistoryPoint(
                    trackerId = "t1",
                    timestampMs = 5L,
                    latitude = 1.0,
                    longitude = 2.0,
                    provenance = TrackerHistoryProvenance.SERVER_GEOMETRY,
                ),
            ),
            committedAtMs = 1L,
            generation = 1L,
            complete = true,
        )
        val snapshots = mapOf(TrackerHistoryKey("t1", window) to snapshot)

        val trails = TrackerHistoryRenderMapper.trailsByTracker(
            snapshots = snapshots,
            trackerIds = listOf("t1"),
            window = window,
            trailPointLimit = 100,
        )

        assertEquals(1, trails["t1"]?.size)
        assertEquals(5L, trails["t1"]?.single()?.time)
    }
}
