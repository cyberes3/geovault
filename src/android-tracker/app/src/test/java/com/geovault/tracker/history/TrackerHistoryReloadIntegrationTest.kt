package com.geovault.tracker.history

import com.geovault.tracker.db.QueuedLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerHistoryReloadIntegrationTest {
    @Test
    fun trunkOverlayClear_roundTripThroughRepository() {
        val repository = TrackerHistoryRepository()
        val dispatcher = TrackerHistoryIntentDispatcher(repository)
        val window = TrackerHistoryWindow("all")
        val trunk = TrackerHistorySourceBatch(
            trackerId = "t1",
            window = window,
            sourceKind = TrackerHistorySourceKind.FILTERED_SERVER_TRUNK,
            points = listOf(
                TrackerHistoryPoint(
                    trackerId = "t1",
                    timestampMs = 1L,
                    latitude = 1.0,
                    longitude = 2.0,
                    provenance = TrackerHistoryProvenance.SERVER_GEOMETRY,
                ),
            ),
            complete = true,
        )
        dispatcher.dispatch(
            TrackerHistoryIntent.CommitTrunk(
                batch = trunk,
                activeSessionStartMs = null,
            ),
        )
        dispatcher.dispatch(
            TrackerHistoryIntent.CommitOverlay(
                batch = TrackerHistorySourceBatch(
                    trackerId = "t1",
                    window = window,
                    sourceKind = TrackerHistorySourceKind.LOCAL_QUEUE,
                    points = listOf(
                        TrackerHistoryPoint(
                            trackerId = "t1",
                            timestampMs = 2L,
                            latitude = 3.0,
                            longitude = 4.0,
                            provenance = TrackerHistoryProvenance.LOCAL_QUEUE,
                        ),
                    ),
                ),
                activeSessionStartMs = null,
            ),
        )
        val composed = repository.snapshotFor(TrackerHistoryKey("t1", window))!!
        assertEquals(listOf(1L, 2L), composed.points.map { it.timestampMs })

        dispatcher.dispatch(
            TrackerHistoryIntent.Clear(
                boundary = TrackerHistoryClearBoundary(
                    trackerId = "t1",
                    clearedAtMs = 10L,
                    activeSessionStartMs = null,
                ),
                window = window,
            ),
        )
        val cleared = repository.snapshotFor(TrackerHistoryKey("t1", window))!!
        assertTrue(cleared.points.isEmpty())

        repository.reset()
        assertTrue(repository.snapshots.value.isEmpty())
    }

    @Test
    fun clearHistory_purgesAllWindowKeysForTracker() {
        val repository = TrackerHistoryRepository()
        val dispatcher = TrackerHistoryIntentDispatcher(repository)
        val trackerId = "t1"
        val windowAll = TrackerHistoryWindow("all")
        val windowWeek = TrackerHistoryWindow("week")
        fun trunkFor(window: TrackerHistoryWindow) = TrackerHistorySourceBatch(
            trackerId = trackerId,
            window = window,
            sourceKind = TrackerHistorySourceKind.FILTERED_SERVER_TRUNK,
            points = listOf(
                TrackerHistoryPoint(
                    trackerId = trackerId,
                    timestampMs = 1L,
                    latitude = 1.0,
                    longitude = 2.0,
                    provenance = TrackerHistoryProvenance.SERVER_GEOMETRY,
                ),
            ),
            complete = true,
        )
        dispatcher.dispatch(TrackerHistoryIntent.CommitTrunk(trunkFor(windowAll), activeSessionStartMs = null))
        dispatcher.dispatch(TrackerHistoryIntent.CommitTrunk(trunkFor(windowWeek), activeSessionStartMs = null))
        assertEquals(2, repository.snapshots.value.keys.count { it.normalizedTrackerId == trackerId })

        dispatcher.dispatch(
            TrackerHistoryIntent.Clear(
                boundary = TrackerHistoryClearBoundary(
                    trackerId = trackerId,
                    clearedAtMs = 5L,
                    activeSessionStartMs = null,
                ),
                window = windowAll,
            ),
        )
        assertEquals(
            setOf(windowAll.normalizedKey),
            repository.snapshots.value.keys
                .filter { it.normalizedTrackerId == trackerId }
                .map { it.window.normalizedKey }
                .toSet(),
        )
        assertEquals(null, repository.snapshotFor(TrackerHistoryKey(trackerId, windowWeek)))
        val cleared = repository.snapshotFor(TrackerHistoryKey(trackerId, windowAll))!!
        assertTrue(cleared.points.isEmpty())
    }

    @Test
    fun degradedLocalOnlyTrunk_isMarkedIncomplete() {
        val batch = TrackerHistorySourceAdapters.degradedLocalOnlyTrunk(
            trackerId = "t1",
            window = TrackerHistoryWindow("all"),
            queuedLocations = listOf(
                QueuedLocation(
                    id = 1L,
                    trackerId = "t1",
                    time = 1L,
                    latitude = 1.0,
                    longitude = 2.0,
                    altitude = null,
                    speed = null,
                    bearing = null,
                    accuracy = null,
                    sat = null,
                    prov = "local_gps",
                    dist = null,
                ),
            ),
        )
        assertFalse(batch.complete)
        assertTrue(batch.degradedLocalOnly)
    }

    @Test
    fun recordStart_overlayBeforeRuntimeSessionStart_composesNonEmptyCurrentSession() {
        val repository = TrackerHistoryRepository()
        val dispatcher = TrackerHistoryIntentDispatcher(repository)
        val trackerId = "t1"
        val window = TrackerHistoryWindow("current_session")
        val sessionStartMs = 5_000L

        dispatcher.dispatch(
            TrackerHistoryIntent.Clear(
                boundary = TrackerHistoryClearBoundary(
                    trackerId = trackerId,
                    clearedAtMs = sessionStartMs,
                    activeSessionStartMs = sessionStartMs,
                ),
                window = window,
            ),
        )
        assertTrue(repository.snapshotFor(TrackerHistoryKey(trackerId, window))!!.points.isEmpty())

        dispatcher.dispatch(
            TrackerHistoryIntent.CommitOverlay(
                batch = TrackerHistorySourceBatch(
                    trackerId = trackerId,
                    window = window,
                    sourceKind = TrackerHistorySourceKind.LOCAL_LIVE,
                    points = listOf(
                        point(
                            trackerId = trackerId,
                            timestampMs = 6_000L,
                            provenance = TrackerHistoryProvenance.LOCAL_LIVE,
                            startTimestampMs = sessionStartMs,
                        ),
                    ),
                ),
                activeSessionStartMs = null,
            ),
        )

        val composed = repository.snapshotFor(TrackerHistoryKey(trackerId, window))!!
        assertEquals(listOf(6_000L), composed.points.map { it.timestampMs })
    }

    @Test
    fun activeRecording_ignoresStaleCurrentSessionServerTrunk() {
        val repository = TrackerHistoryRepository()
        val dispatcher = TrackerHistoryIntentDispatcher(repository)
        val trackerId = "t1"
        val window = TrackerHistoryWindow("current_session")
        val activeSessionStartMs = 1_000L

        dispatcher.dispatch(
            TrackerHistoryIntent.CommitTrunk(
                batch = serverTrunk(
                    trackerId = trackerId,
                    window = window,
                    times = listOf(100L, 200L),
                    startTimestampMs = 100L,
                ),
                activeSessionStartMs = null,
            )
        )
        assertEquals(listOf(100L, 200L), repository.snapshotFor(TrackerHistoryKey(trackerId, window))!!.points.map { it.timestampMs })

        dispatcher.dispatch(
            TrackerHistoryIntent.Clear(
                boundary = TrackerHistoryClearBoundary(
                    trackerId = trackerId,
                    clearedAtMs = activeSessionStartMs,
                    activeSessionStartMs = activeSessionStartMs,
                ),
                window = window,
            )
        )
        assertTrue(repository.snapshotFor(TrackerHistoryKey(trackerId, window))!!.points.isEmpty())

        val ignored = dispatcher.dispatch(
            TrackerHistoryIntent.CommitTrunk(
                batch = serverTrunk(
                    trackerId = trackerId,
                    window = window,
                    times = listOf(100L, 200L),
                    startTimestampMs = 100L,
                ),
                activeSessionStartMs = activeSessionStartMs,
            )
        )
        assertFalse(ignored.committed)
        assertEquals("stale_trunk_before_active_session", ignored.reason)
        assertTrue(repository.snapshotFor(TrackerHistoryKey(trackerId, window))!!.points.isEmpty())

        dispatcher.dispatch(
            TrackerHistoryIntent.CommitOverlay(
                batch = TrackerHistorySourceBatch(
                    trackerId = trackerId,
                    window = window,
                    sourceKind = TrackerHistorySourceKind.LOCAL_LIVE,
                    points = listOf(
                        point(
                            trackerId = trackerId,
                            timestampMs = 1_200L,
                            provenance = TrackerHistoryProvenance.LOCAL_LIVE,
                            startTimestampMs = activeSessionStartMs,
                        )
                    ),
                ),
                activeSessionStartMs = activeSessionStartMs,
            )
        )
        assertEquals(listOf(1_200L), repository.snapshotFor(TrackerHistoryKey(trackerId, window))!!.points.map { it.timestampMs })
    }

    private fun serverTrunk(
        trackerId: String,
        window: TrackerHistoryWindow,
        times: List<Long>,
        startTimestampMs: Long?,
    ): TrackerHistorySourceBatch {
        return TrackerHistorySourceBatch(
            trackerId = trackerId,
            window = window,
            sourceKind = TrackerHistorySourceKind.FILTERED_SERVER_TRUNK,
            points = times.map {
                point(
                    trackerId = trackerId,
                    timestampMs = it,
                    provenance = TrackerHistoryProvenance.SERVER_GEOMETRY,
                    startTimestampMs = startTimestampMs,
                )
            },
            complete = true,
        )
    }

    private fun point(
        trackerId: String,
        timestampMs: Long,
        provenance: TrackerHistoryProvenance,
        startTimestampMs: Long? = null,
    ): TrackerHistoryPoint {
        return TrackerHistoryPoint(
            trackerId = trackerId,
            timestampMs = timestampMs,
            latitude = 1.0,
            longitude = 2.0,
            startTimestampMs = startTimestampMs,
            provenance = provenance,
        )
    }
}
