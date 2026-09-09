package com.geovault.tracker.data

import com.geovault.tracker.Tracker
import com.geovault.tracker.history.TrunkFetchOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CatalogBootstrapTest {

    @Test
    fun refreshForLaunch_runsSingleFlightAcrossConcurrentCallers() = runBlocking {
        val source = FakeBootstrapDataSource()
        source.trackersGate = CompletableDeferred()
        val bootstrap = CatalogBootstrap(
            dataSource = source,
            scope = this,
        )
        val callers = List(4) { async { bootstrap.refreshForLaunch() } }
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
        val bootstrap = CatalogBootstrap(
            dataSource = source,
            scope = this,
        )

        val outcome = bootstrap.refreshForResume()

        assertTrue(outcome.isServerAccessible)
        assertEquals(1, source.loadTrackersCalls.get())
        assertEquals(1, source.loadGroupsCalls.get())
        assertEquals(1, source.loadMapVisibilityCalls.get())
    }

    @Test
    fun refreshForLaunch_fetchesCatalogGeometryOnce() = runBlocking {
        val source = FakeBootstrapDataSource()
        val geometryCalls = AtomicInteger(0)
        val trackers = listOf(Tracker(id = "t1", name = "One", color = null))
        val bootstrap = CatalogBootstrap(
            dataSource = source,
            scope = this,
            catalogTrackers = { trackers },
            fetchCatalogGeometry = {
                geometryCalls.incrementAndGet()
                assertEquals(trackers, it)
                TrunkFetchOutcome(failure = null, committedNewDegrade = false)
            },
        )

        val first = bootstrap.refreshForLaunch()
        val second = bootstrap.refreshForLaunch()

        assertEquals(1, geometryCalls.get())
        assertNull(first.geometryFailure)
        assertNull(second.geometryFailure)
        assertTrue(first.isServerAccessible)
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
