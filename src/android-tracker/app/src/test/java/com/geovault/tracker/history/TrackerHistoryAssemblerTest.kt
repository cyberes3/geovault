package com.geovault.tracker.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerHistoryAssemblerTest {
    @Test
    fun compose_appendsEligibleOverlayAfterBoundedServerTrunk() {
        val key = TrackerHistoryKey("tracker-1", TrackerHistoryWindow("1h"))
        val trunk = batch(
            kind = TrackerHistorySourceKind.FILTERED_SERVER_TRUNK,
            provenance = TrackerHistoryProvenance.SERVER_GEOMETRY,
            times = listOf(10_000L, 20_000L),
            complete = false,
        )
        val overlay = batch(
            kind = TrackerHistorySourceKind.LOCAL_QUEUE,
            provenance = TrackerHistoryProvenance.LOCAL_QUEUE,
            times = listOf(20_000L, 30_000L),
        )

        val result = TrackerHistoryAssembler.compose(
            TrackerHistoryComposeInput(
                key = key,
                trunk = trunk,
                overlayBatches = listOf(overlay),
                nowMs = 31_000L,
            )
        )

        assertTrue(result.committed)
        assertFalse(result.snapshot.complete)
        assertEquals(listOf(10_000L, 20_000L, 30_000L), result.snapshot.points.map { it.timestampMs })
        assertEquals(listOf(30_000L), result.snapshot.overlay.map { it.timestampMs })
    }

    @Test
    fun compose_appliesClearBoundaryWithoutDroppingActiveSessionPoints() {
        val activeSessionStart = 5_000L
        val key = TrackerHistoryKey("tracker-1", TrackerHistoryWindow("session"))
        val overlay = TrackerHistorySourceBatch(
            trackerId = "tracker-1",
            window = key.window,
            sourceKind = TrackerHistorySourceKind.LOCAL_QUEUE,
            points = listOf(
                point(9_000L, startTimestampMs = 1_000L),
                point(10_000L, startTimestampMs = activeSessionStart),
                point(12_000L, startTimestampMs = 1_000L),
            ),
        )

        val result = TrackerHistoryAssembler.compose(
            TrackerHistoryComposeInput(
                key = key,
                trunk = null,
                overlayBatches = listOf(overlay),
                activeSessionStartMs = activeSessionStart,
                clearBoundary = TrackerHistoryClearBoundary(
                    trackerId = "tracker-1",
                    clearedAtMs = 11_000L,
                    activeSessionStartMs = activeSessionStart,
                ),
                nowMs = 13_000L,
            )
        )

        assertEquals(listOf(10_000L), result.snapshot.points.map { it.timestampMs })
    }

    private fun batch(
        kind: TrackerHistorySourceKind,
        provenance: TrackerHistoryProvenance,
        times: List<Long>,
        complete: Boolean = true,
    ): TrackerHistorySourceBatch {
        return TrackerHistorySourceBatch(
            trackerId = "tracker-1",
            window = TrackerHistoryWindow("1h"),
            sourceKind = kind,
            points = times.map { point(it, provenance) },
            complete = complete,
        )
    }

    private fun point(
        timestampMs: Long,
        provenance: TrackerHistoryProvenance = TrackerHistoryProvenance.LOCAL_QUEUE,
        startTimestampMs: Long? = null,
    ): TrackerHistoryPoint {
        return TrackerHistoryPoint(
            trackerId = "tracker-1",
            timestampMs = timestampMs,
            latitude = 35.0 + timestampMs / 100_000.0,
            longitude = -106.0 - timestampMs / 100_000.0,
            startTimestampMs = startTimestampMs,
            provenance = provenance,
        )
    }
}
