package com.geovault.tracker.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TrackerBootstrapOrchestratorTest {

    @Test
    fun refreshForLaunch_runsSingleFlightAcrossConcurrentCallers() = runBlocking {
        val source = FakeBootstrapDataSource()
        source.trackersGate = CompletableDeferred()
        val orchestrator = TrackerBootstrapOrchestrator(
            dataSource = source,
            scope = this,
        )
        val callers = List(4) { async { orchestrator.refreshForLaunch() } }
        delay(30L)
        source.trackersGate?.complete(Unit)
        val outcomes = callers.awaitAll()

        assertEquals(1, source.loadTrackersCalls.get())
        assertEquals(1, source.loadGroupsCalls.get())
        assertEquals(1, source.loadMapVisibilityCalls.get())
        assertTrue(outcomes.all { it.isServerAccessible })
    }

    @Test
    fun refreshForResume_skipsSelectedTrackerPrefetch() = runBlocking {
        val source = FakeBootstrapDataSource()
        val orchestrator = TrackerBootstrapOrchestrator(
            dataSource = source,
            scope = this,
        )

        val outcome = orchestrator.refreshForResume()

        assertTrue(outcome.isServerAccessible)
        assertEquals(1, source.loadTrackersCalls.get())
        assertEquals(1, source.loadGroupsCalls.get())
        assertEquals(1, source.loadMapVisibilityCalls.get())
    }

    private class FakeBootstrapDataSource : TrackerBootstrapDataSource {
        val loadTrackersCalls = AtomicInteger(0)
        val loadGroupsCalls = AtomicInteger(0)
        val loadMapVisibilityCalls = AtomicInteger(0)
        var trackersGate: CompletableDeferred<Unit>? = null

        override suspend fun loadTrackers(forceRefresh: Boolean) {
            loadTrackersCalls.incrementAndGet()
            trackersGate?.await()
        }

        override suspend fun loadGroups(forceRefresh: Boolean) {
            loadGroupsCalls.incrementAndGet()
        }

        override suspend fun loadMapVisibility(forceRefresh: Boolean) {
            loadMapVisibilityCalls.incrementAndGet()
        }

    }
}
