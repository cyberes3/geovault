package com.geovault.tracker.data

import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.presentation.TrackerMapTrailDataCoordinator
import com.geovault.tracker.presentation.TrackerMapSessionRequestDeduper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Collections

class LaunchNetworkCallDedupeSimulationTest {

    @Test
    fun launchSession_recordsNoDuplicateEndpointCalls_acrossBootstrapAndMapWarmup() = runBlocking {
        val trackerId = "80542db3-e986-4303-a26b-585505d658ad"
        val recorder = EndpointRecorder()
        val dataSource = RecordingBootstrapDataSource(recorder)
        val orchestrator = TrackerBootstrapOrchestrator(
            dataSource = dataSource,
        )
        val deduper = TrackerMapSessionRequestDeduper(
            dedupeWindowMs = 10_000L,
            nowMsProvider = { 1_000L }
        )

        // Three launch surfaces racing at startup.
        listOf(
            async(Dispatchers.Default) { orchestrator.refreshForLaunch() },
            async(Dispatchers.Default) { orchestrator.refreshForLaunch() },
            async(Dispatchers.Default) { orchestrator.refreshForLaunch() },
        ).awaitAll()

        // Simulate map warmup reload bursts during launch stabilization.
        repeat(3) {
            simulateMapSingleTrackerWarmup(
                trackerId = trackerId,
                recorder = recorder,
                deduper = deduper
            )
        }

        // Desired invariant: launch session should hit each endpoint at most once.
        recorder.assertNoDuplicates("launch")
    }

    @Test
    fun resumeSession_recordsNoDuplicateEndpointCalls_acrossConcurrentResumeAndMapWarmup() = runBlocking {
        val trackerId = "80542db3-e986-4303-a26b-585505d658ad"
        val recorder = EndpointRecorder()
        val dataSource = RecordingBootstrapDataSource(recorder)
        val orchestrator = TrackerBootstrapOrchestrator(
            dataSource = dataSource,
        )
        val deduper = TrackerMapSessionRequestDeduper(
            dedupeWindowMs = 10_000L,
            nowMsProvider = { 2_000L }
        )

        listOf(
            async(Dispatchers.Default) { orchestrator.refreshForResume() },
            async(Dispatchers.Default) { orchestrator.refreshForResume() },
            async(Dispatchers.Default) { orchestrator.refreshForResume() },
        ).awaitAll()

        repeat(2) {
            simulateMapSingleTrackerWarmup(
                trackerId = trackerId,
                recorder = recorder,
                deduper = deduper
            )
        }

        recorder.assertNoDuplicates("resume")
    }

    @Test
    fun launchThenResumeWithTabOverlap_recordsNoDuplicateCalls_perSessionWindow() = runBlocking {
        val trackerId = "80542db3-e986-4303-a26b-585505d658ad"
        val recorder = EndpointRecorder()
        val dataSource = RecordingBootstrapDataSource(recorder)
        val orchestrator = TrackerBootstrapOrchestrator(
            dataSource = dataSource,
        )
        val deduper = TrackerMapSessionRequestDeduper(
            dedupeWindowMs = 10_000L,
            nowMsProvider = { 3_000L }
        )

        // launch bootstrap + trackers/shared preload overlap
        listOf(
            async(Dispatchers.Default) { orchestrator.refreshForLaunch() },
            async(Dispatchers.Default) { orchestrator.refreshForLaunch() },
            async(Dispatchers.Default) { orchestrator.refreshForLaunch() },
        ).awaitAll()
        simulateMapSingleTrackerWarmup(
            trackerId = trackerId,
            recorder = recorder,
            deduper = deduper
        )
        recorder.assertNoDuplicates("launch-window")

        // emulate a new visibility window after app background/foreground
        recorder.clear()
        listOf(
            async(Dispatchers.Default) { orchestrator.refreshForResume() },
            async(Dispatchers.Default) { orchestrator.refreshForResume() },
        ).awaitAll()
        repeat(2) {
            simulateMapSingleTrackerWarmup(
                trackerId = trackerId,
                recorder = recorder,
                deduper = deduper
            )
        }
        recorder.assertNoDuplicates("resume-window")
    }

    @Test
    fun modeSwitchBurst_singleTrackerToGroupToAll_recordsNoDuplicateTrailEndpoints() = runBlocking {
        val trackerId = "80542db3-e986-4303-a26b-585505d658ad"
        val recorder = EndpointRecorder()
        val deduper = TrackerMapSessionRequestDeduper(
            dedupeWindowMs = 10_000L,
            nowMsProvider = { 4_000L }
        )

        // single-session
        simulateMapSingleTrackerWarmup(
            trackerId = trackerId,
            recorder = recorder,
            deduper = deduper
        )
        // group-mode burst (still depends on selected tracker trail bootstrap)
        simulateMapSingleTrackerWarmup(
            trackerId = trackerId,
            recorder = recorder,
            deduper = deduper
        )
        // all-mode burst
        simulateMapSingleTrackerWarmup(
            trackerId = trackerId,
            recorder = recorder,
            deduper = deduper
        )

        recorder.assertNoDuplicates("mode-switch-burst")
    }

    private class RecordingBootstrapDataSource(
        private val recorder: EndpointRecorder,
    ) : TrackerBootstrapDataSource {
        override suspend fun loadTrackers(forceRefresh: Boolean): RepositoryResult<*> {
            recorder.record("GET /api/extensions/live-track/trackers/")
            return RepositoryResult.Success(Unit)
        }

        override suspend fun loadGroups(forceRefresh: Boolean): RepositoryResult<*> {
            recorder.record("GET /api/extensions/live-track/groups/")
            return RepositoryResult.Success(Unit)
        }

        override suspend fun loadMapVisibility(forceRefresh: Boolean): RepositoryResult<*> {
            recorder.record("GET /api/extensions/live-track/map-visibility/")
            return RepositoryResult.Success(Unit)
        }

    }

    private suspend fun simulateMapSingleTrackerWarmup(
        trackerId: String,
        recorder: EndpointRecorder,
        deduper: TrackerMapSessionRequestDeduper,
    ) {
        TrackerMapTrailDataCoordinator.loadSingleTrackerTrail(
            trackerId = trackerId,
            loadTrackerGeometry = { id ->
                deduper.loadOnce("geometry:$id") {
                    recorder.record("GET /api/extensions/live-track/trackers/$id/geometry/")
                    RepositoryResult.Success(Tracker(id = id, name = "Tracker", color = null))
                }
            },
            loadQueueTrailWithOverlay = { emptyList<QueuedLocation>() },
            resolveSessionStartMs = { null },
            onSessionStartResolved = { _, _ -> Unit },
            onSessionAnchorResolved = { _ -> Unit },
            mapCoordinatesToTrail = { _, _ -> emptyList() },
        )
    }

    private class EndpointRecorder {
        private val calls = Collections.synchronizedList(mutableListOf<String>())

        fun record(endpoint: String) {
            calls.add(endpoint)
        }

        fun clear() {
            calls.clear()
        }

        fun assertNoDuplicates(windowName: String) {
            val endpointCounts = calls.groupingBy { it }.eachCount()
            endpointCounts.forEach { (endpoint, count) ->
                assertEquals(
                    "Duplicate endpoint call in $windowName: $endpoint",
                    1,
                    count
                )
            }
        }
    }
}
