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
                sessionContext = sessionContext(key.window),
                nowMs = 31_000L,
            )
        )

        assertTrue(result.committed)
        assertFalse(result.snapshot.complete)
        assertEquals(listOf(10_000L, 20_000L, 30_000L), result.snapshot.points.map { it.timestampMs })
        assertEquals(listOf(30_000L), result.snapshot.overlay.map { it.timestampMs })
    }

    @Test
    fun compose_currentSession_keepsOnlyActiveSessionOverlayPoints() {
        val activeSessionStart = 50_000L
        val key = TrackerHistoryKey("tracker-1", TrackerHistoryWindow("current_session"))
        val overlay = TrackerHistorySourceBatch(
            trackerId = "tracker-1",
            window = key.window,
            sourceKind = TrackerHistorySourceKind.LOCAL_LIVE,
            points = listOf(
                point(10_000L, provenance = TrackerHistoryProvenance.LOCAL_LIVE, startTimestampMs = 1_000L),
                point(60_000L, provenance = TrackerHistoryProvenance.LOCAL_LIVE, startTimestampMs = activeSessionStart),
            ),
        )

        val result = TrackerHistoryAssembler.compose(
            TrackerHistoryComposeInput(
                key = key,
                trunk = null,
                overlayBatches = listOf(overlay),
                sessionContext = sessionContext(key.window, activeSessionStartMs = activeSessionStart),
                nowMs = 61_000L,
            )
        )

        assertTrue(result.committed)
        assertEquals(listOf(60_000L), result.snapshot.points.map { it.timestampMs })
    }

    @Test
    fun compose_appliesClearBoundaryWithoutDroppingActiveSessionPoints() {
        val activeSessionStart = 5_000L
        val key = TrackerHistoryKey("tracker-1", TrackerHistoryWindow("current_session"))
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
                sessionContext = sessionContext(key.window, activeSessionStartMs = activeSessionStart),
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

    @Test
    fun compose_currentSession_resolvesSessionStartFromOverlayWhenRuntimeStartNull() {
        val sessionStartMs = 50_000L
        val key = TrackerHistoryKey("tracker-1", TrackerHistoryWindow("current_session"))
        val overlay = TrackerHistorySourceBatch(
            trackerId = "tracker-1",
            window = key.window,
            sourceKind = TrackerHistorySourceKind.LOCAL_LIVE,
            points = listOf(
                point(10_000L, provenance = TrackerHistoryProvenance.LOCAL_LIVE, startTimestampMs = 1_000L),
                point(60_000L, provenance = TrackerHistoryProvenance.LOCAL_LIVE, startTimestampMs = sessionStartMs),
            ),
        )
        val previous = TrackerHistorySnapshot(
            key = key,
            trunk = emptyList(),
            overlay = emptyList(),
            points = emptyList(),
            committedAtMs = 1L,
            generation = 1L,
        )

        val result = TrackerHistoryAssembler.compose(
            TrackerHistoryComposeInput(
                key = key,
                trunk = null,
                overlayBatches = listOf(overlay),
                sessionContext = sessionContext(key.window, activeSessionStartMs = null),
                nowMs = 61_000L,
                previousSnapshot = previous,
            ),
        )

        assertTrue(result.committed)
        assertEquals("composed", result.reason)
        assertEquals(listOf(60_000L), result.snapshot.points.map { it.timestampMs })
        assertTrue(result.snapshot.trunk.isEmpty())
        assertEquals(listOf(60_000L), result.snapshot.overlay.map { it.timestampMs })
    }

    @Test
    fun compose_serverTrunkWithNewerLocalOverlay_appendsEligibleOverlay() {
        val activeStart = 5_000L
        val key = TrackerHistoryKey("tracker-1", TrackerHistoryWindow("current_session"))
        val trunk = TrackerHistorySourceBatch(
            trackerId = "tracker-1",
            window = key.window,
            sourceKind = TrackerHistorySourceKind.FILTERED_SERVER_TRUNK,
            points = listOf(50L, 60L, 70L).map {
                point(it, TrackerHistoryProvenance.SERVER_GEOMETRY, activeStart)
            },
        )
        val overlay = TrackerHistorySourceBatch(
            trackerId = "tracker-1",
            window = key.window,
            sourceKind = TrackerHistorySourceKind.LOCAL_QUEUE,
            points = listOf(point(80L, TrackerHistoryProvenance.LOCAL_QUEUE, activeStart)),
        )

        val result = TrackerHistoryAssembler.compose(
            TrackerHistoryComposeInput(
                key = key,
                trunk = trunk,
                overlayBatches = listOf(overlay),
                sessionContext = sessionContext(key.window, activeSessionStartMs = activeStart),
                nowMs = 100L,
            ),
        )

        assertTrue(result.committed)
        assertEquals(listOf(50L, 60L, 70L, 80L), result.snapshot.points.map { it.timestampMs })
    }

    @Test
    fun compose_emptyResultWithPreviousSnapshot_defers() {
        val key = TrackerHistoryKey("tracker-1", TrackerHistoryWindow("1h"))
        val previous = TrackerHistorySnapshot(
            key = key,
            trunk = listOf(point(10_000L, provenance = TrackerHistoryProvenance.SERVER_GEOMETRY)),
            overlay = emptyList(),
            points = listOf(point(10_000L, provenance = TrackerHistoryProvenance.SERVER_GEOMETRY)),
            committedAtMs = 1L,
            generation = 1L,
        )

        val result = TrackerHistoryAssembler.compose(
            TrackerHistoryComposeInput(
                key = key,
                trunk = null,
                overlayBatches = emptyList(),
                sessionContext = sessionContext(key.window),
                nowMs = 20_000L,
                previousSnapshot = previous,
            ),
        )

        assertEquals(false, result.committed)
        assertEquals("empty_snapshot_deferred", result.reason)
        assertEquals(previous.points.size, result.snapshot.points.size)
    }

    @Test
    fun compose_forceCommitEmpty_commitsEmptyInsteadOfDeferring() {
        val key = TrackerHistoryKey("tracker-1", TrackerHistoryWindow("1h"))
        val previous = TrackerHistorySnapshot(
            key = key,
            trunk = listOf(point(10_000L, provenance = TrackerHistoryProvenance.SERVER_GEOMETRY)),
            overlay = emptyList(),
            points = listOf(point(10_000L, provenance = TrackerHistoryProvenance.SERVER_GEOMETRY)),
            committedAtMs = 1L,
            generation = 1L,
        )

        val result = TrackerHistoryAssembler.compose(
            TrackerHistoryComposeInput(
                key = key,
                trunk = null,
                overlayBatches = emptyList(),
                sessionContext = sessionContext(key.window),
                nowMs = 20_000L,
                previousSnapshot = previous,
                forceCommitEmpty = true,
            ),
        )

        assertTrue(result.committed)
        assertEquals("forced_empty_commit", result.reason)
        assertTrue(result.snapshot.points.isEmpty())
    }

    @Test
    fun compose_completeServerTrunk_skipsClientWindowFilterAtComposeOnly() {
        val key = TrackerHistoryKey("tracker-1", TrackerHistoryWindow("1w"))
        val nowMs = 10_000_000L
        val trunk = TrackerHistorySourceBatch(
            trackerId = "tracker-1",
            window = key.window,
            sourceKind = TrackerHistorySourceKind.FILTERED_SERVER_TRUNK,
            points = listOf(
                point(nowMs - 3 * 24 * 60 * 60 * 1000L),
                point(nowMs - 1_000L),
            ),
            complete = true,
            skipRenderWindowFilter = true,
        )

        val result = TrackerHistoryAssembler.compose(
            TrackerHistoryComposeInput(
                key = key,
                trunk = trunk,
                overlayBatches = emptyList(),
                sessionContext = TrackerHistorySessionContext(
                    activeSessionStartMs = null,
                    window = key.window,
                    skipRenderWindowFilter = true,
                ),
                nowMs = nowMs,
            ),
        )

        assertTrue(result.committed)
        assertTrue(result.snapshot.renderWindowFilterSkipped)
        assertEquals(2, result.snapshot.points.size)
    }

    private fun sessionContext(
        window: TrackerHistoryWindow,
        activeSessionStartMs: Long? = null,
        skipRenderWindowFilter: Boolean = false,
    ): TrackerHistorySessionContext {
        return TrackerHistorySessionContext(
            activeSessionStartMs = activeSessionStartMs,
            window = window,
            skipRenderWindowFilter = skipRenderWindowFilter,
        )
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
