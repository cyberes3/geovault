package com.geovault.tracker.history

import com.geovault.tracker.Tracker
import com.geovault.tracker.services.RecordingRuntime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerHistorySessionBoundaryTest {
    @Test
    fun onRecordingStarted_recomposesWithoutClearingSnapshots() {
        val repository = TrackerHistoryRepository()
        val dispatcher = TrackerHistoryIntentDispatcher(repository)
        val boundary = TrackerHistorySessionBoundary()
        val trackerId = "t1"
        val window = TrackerHistoryWindow("current_session")
        val sessionStart = 10_000L
        dispatcher.dispatch(
            TrackerHistoryIntent.CommitOverlay(
                batch = TrackerHistorySourceBatch(
                    trackerId = trackerId,
                    window = window,
                    sourceKind = TrackerHistorySourceKind.LOCAL_QUEUE,
                    points = listOf(
                        TrackerHistoryPoint(
                            trackerId = trackerId,
                            timestampMs = sessionStart + 1_000L,
                            latitude = 1.0,
                            longitude = 2.0,
                            provenance = TrackerHistoryProvenance.LOCAL_QUEUE,
                            startTimestampMs = sessionStart,
                        ),
                    ),
                ),
                activeSessionStartMs = sessionStart,
            ),
        )
        assertTrue(repository.snapshots.value.isNotEmpty())

        boundary.onRecordingStarted(
            trackerId = trackerId,
            trackers = listOf(tracker(id = trackerId)),
            sessionStartMs = sessionStart,
            repository = repository,
        )

        assertTrue(repository.snapshots.value.isNotEmpty())
        val snapshot = repository.snapshotFor(TrackerHistoryKey(trackerId, window))
        assertTrue(snapshot != null && snapshot.points.isNotEmpty())
    }

    @Test
    fun onRecordingStopped_clearsSnapshotsForTracker() {
        val repository = TrackerHistoryRepository()
        val dispatcher = TrackerHistoryIntentDispatcher(repository)
        val boundary = TrackerHistorySessionBoundary()
        val trackerId = "t1"
        val window = TrackerHistoryWindow("current_session")
        dispatcher.dispatch(
            TrackerHistoryIntent.CommitOverlay(
                batch = TrackerHistorySourceBatch(
                    trackerId = trackerId,
                    window = window,
                    sourceKind = TrackerHistorySourceKind.LOCAL_QUEUE,
                    points = listOf(
                        TrackerHistoryPoint(
                            trackerId = trackerId,
                            timestampMs = 1L,
                            latitude = 1.0,
                            longitude = 2.0,
                            provenance = TrackerHistoryProvenance.LOCAL_QUEUE,
                        ),
                    ),
                ),
                activeSessionStartMs = null,
            ),
        )
        assertTrue(repository.snapshots.value.isNotEmpty())

        boundary.onRecordingStopped(
            trackerId = trackerId,
            trackers = listOf(tracker(id = trackerId)),
            dispatcher = dispatcher,
        )

        val snapshot = repository.snapshotFor(TrackerHistoryKey(trackerId, window))
        assertTrue(snapshot == null || snapshot.points.isEmpty())
    }

    @Test
    fun onRuntimeUpdated_flushesPendingRecomposeWhenSessionStartArrives() {
        val repository = TrackerHistoryRepository()
        val dispatcher = TrackerHistoryIntentDispatcher(repository)
        val boundary = TrackerHistorySessionBoundary()
        val trackerId = "t1"
        val sessionStart = 20_000L
        val window = TrackerHistoryWindow("current_session")
        dispatcher.dispatch(
            TrackerHistoryIntent.CommitOverlay(
                batch = TrackerHistorySourceBatch(
                    trackerId = trackerId,
                    window = window,
                    sourceKind = TrackerHistorySourceKind.LOCAL_QUEUE,
                    points = listOf(
                        TrackerHistoryPoint(
                            trackerId = trackerId,
                            timestampMs = sessionStart + 500L,
                            latitude = 1.0,
                            longitude = 2.0,
                            provenance = TrackerHistoryProvenance.LOCAL_QUEUE,
                            startTimestampMs = sessionStart,
                        ),
                    ),
                ),
                activeSessionStartMs = null,
            ),
        )

        boundary.onRecordingStarted(
            trackerId = trackerId,
            trackers = listOf(tracker(id = trackerId)),
            sessionStartMs = null,
            repository = repository,
        )

        boundary.onRuntimeUpdated(
            runtime = recordingRuntime(trackerId = trackerId, sessionStartMs = sessionStart),
            trackers = listOf(tracker(id = trackerId)),
            repository = repository,
        )

        val snapshot = repository.snapshotFor(
            TrackerHistoryKey(trackerId, TrackerHistoryWindow("current_session")),
        )
        assertTrue(snapshot != null && snapshot.points.isNotEmpty())
    }

    private fun tracker(id: String) = Tracker(
        id = id,
        name = "Test",
        color = "#0000ff",
        created_at = null,
        updated_at = null,
        settings = mapOf("recent_data_window" to "current_session"),
        geometry = null,
        point_params = null,
        geometry_status = null,
        last_point = null,
        bbox = null,
        tracker_secret = null,
    )

    private fun recordingRuntime(trackerId: String, sessionStartMs: Long) =
        com.geovault.tracker.services.TrackingRuntimeSnapshot(
            recordingRuntime = RecordingRuntime(
                sessionActive = true,
                selectedTrackerId = trackerId,
            ),
            sessionStartTimeMs = sessionStartMs,
            selectedTrackerId = trackerId,
        )
}
