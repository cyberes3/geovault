package com.geovault.tracker.history

import com.geovault.tracker.GeoJsonLineString
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerGeometryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerHistorySourceAdaptersTest {
    @Test
    fun filteredServerTrunk_keepsBoundedStatusAsIncompleteWithoutRequestingAllHistory() {
        val tracker = Tracker(
            id = "tracker-1",
            name = "Tracker",
            color = null,
            settings = mapOf("recent_data_window" to "1h"),
            geometry = GeoJsonLineString(
                type = "LineString",
                coordinates = listOf(
                    listOf(-106.0, 35.0, 1_000.0),
                    listOf(-106.1, 35.1, 2_000.0),
                ),
            ),
            point_params = listOf(
                mapOf("starttimestamp" to 1000L, "acc" to 4.5),
                mapOf("starttimestamp" to 1000L, "acc" to 3.5),
            ),
            geometry_status = TrackerGeometryStatus(
                window = "1h",
                returned_count = 2,
                total_filtered_count = 25,
                is_truncated = true,
            ),
        )

        val batch = TrackerHistorySourceAdapters.filteredServerTrunk(tracker, fetchedAtMs = 3_000_000L)

        assertEquals(TrackerHistorySourceKind.FILTERED_SERVER_TRUNK, batch.sourceKind)
        assertEquals("1h", batch.window.normalizedKey)
        assertEquals(listOf(1_000_000L, 2_000_000L), batch.points.map { it.timestampMs })
        assertEquals(listOf(1_000_000L, 1_000_000L), batch.points.map { it.startTimestampMs })
        assertFalse(batch.complete)
    }

    @Test
    fun filteredServerTrunk_readsSpeedFromSpdKphInMetersPerSecond() {
        val tracker = Tracker(
            id = "tracker-1",
            name = "Tracker",
            color = null,
            geometry = GeoJsonLineString(
                type = "LineString",
                coordinates = listOf(listOf(-106.0, 35.0, 1_000_000L.toDouble())),
            ),
            point_params = listOf(mapOf("spd_kph" to 36.0)),
        )

        val batch = TrackerHistorySourceAdapters.filteredServerTrunk(tracker)

        assertEquals(10f, batch.points.single().speed)
    }

    @Test
    fun filteredServerTrunk_marksCompleteWhenServerDidNotTruncate() {
        val tracker = Tracker(
            id = "tracker-1",
            name = "Tracker",
            color = null,
            geometry = GeoJsonLineString(
                type = "LineString",
                coordinates = listOf(listOf(-106.0, 35.0, 1_000_000L.toDouble())),
            ),
            geometry_status = TrackerGeometryStatus(
                window = "all",
                returned_count = 1,
                total_filtered_count = 1,
                is_truncated = false,
            ),
        )

        val batch = TrackerHistorySourceAdapters.filteredServerTrunk(tracker)

        assertTrue(batch.complete)
        assertEquals(1, batch.points.size)
    }
}
