package com.geovault.tracker.data

import com.geovault.tracker.history.TrunkFetchOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Collections

class LaunchNetworkCallDedupeSimulationTest {

    @Test
    fun launchSession_recordsNoDuplicateEndpointCalls() = runBlocking {
        val recorder = EndpointRecorder()
        val orchestrator = catalogBootstrap(this, recorder)

        listOf(
            async(Dispatchers.Default) { orchestrator.refreshForLaunch() },
            async(Dispatchers.Default) { orchestrator.refreshForLaunch() },
            async(Dispatchers.Default) { orchestrator.refreshForLaunch() },
        ).awaitAll()

        recorder.assertNoDuplicates("launch")
    }

    @Test
    fun resumeSession_recordsNoDuplicateEndpointCalls() = runBlocking {
        val recorder = EndpointRecorder()
        val orchestrator = catalogBootstrap(this, recorder)

        listOf(
            async(Dispatchers.Default) { orchestrator.refreshForResume() },
            async(Dispatchers.Default) { orchestrator.refreshForResume() },
            async(Dispatchers.Default) { orchestrator.refreshForResume() },
        ).awaitAll()

        recorder.assertNoDuplicates("resume")
    }

    @Test
    fun launchThenResumeWithTabOverlap_recordsNoDuplicateCalls_perSessionWindow() = runBlocking {
        val recorder = EndpointRecorder()
        val orchestrator = catalogBootstrap(this, recorder)

        listOf(
            async(Dispatchers.Default) { orchestrator.refreshForLaunch() },
            async(Dispatchers.Default) { orchestrator.refreshForLaunch() },
            async(Dispatchers.Default) { orchestrator.refreshForLaunch() },
        ).awaitAll()
        recorder.assertNoDuplicates("launch-window")

        recorder.clear()
        listOf(
            async(Dispatchers.Default) { orchestrator.refreshForResume() },
            async(Dispatchers.Default) { orchestrator.refreshForResume() },
        ).awaitAll()
        recorder.assertNoDuplicates("resume-window")
    }

    private fun catalogBootstrap(
        scope: kotlinx.coroutines.CoroutineScope,
        recorder: EndpointRecorder,
    ): CatalogBootstrap {
        return CatalogBootstrap(
            dataSource = RecordingBootstrapDataSource(recorder),
            scope = scope,
            fetchCatalogGeometry = {
                recorder.record("POST /api/extensions/live-track/trackers/geometry/")
                TrunkFetchOutcome(failure = null, committedNewDegrade = false)
            },
        )
    }

    private class RecordingBootstrapDataSource(
        private val recorder: EndpointRecorder,
    ) : TrackerBootstrapDataSource {
        override suspend fun loadTrackers(forceRefresh: Boolean) {
            recorder.record("GET /api/extensions/live-track/trackers/")
        }

        override suspend fun loadGroups(forceRefresh: Boolean) {
            recorder.record("GET /api/extensions/live-track/groups/")
        }

        override suspend fun loadMapVisibility(forceRefresh: Boolean) {
            recorder.record("GET /api/extensions/live-track/map-visibility/")
        }
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
