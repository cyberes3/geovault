package com.geovault.tracker.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CatalogBootstrapWarmupTest {

    @Test
    fun runLaunchWarmup_loadsCatalogOnce() = runTest {
        val source = FakeBootstrapDataSource()
        val bootstrap = CatalogBootstrap(dataSource = source, scope = this)

        val outcome = bootstrap.runLaunchWarmup()

        assertTrue(outcome.isServerAccessible)
        assertEquals(1, source.loadTrackersCalls.get())
        assertEquals(1, source.loadGroupsCalls.get())
        assertEquals(1, source.loadMapVisibilityCalls.get())
    }

    @Test
    fun resetForSignedOutSession_thenRunLaunchWarmup_loadsAgain() = runTest {
        val source = FakeBootstrapDataSource()
        val bootstrap = CatalogBootstrap(dataSource = source, scope = this)

        bootstrap.runLaunchWarmup()
        assertEquals(1, source.loadTrackersCalls.get())

        bootstrap.resetForSignedOutSession()
        bootstrap.runLaunchWarmup()

        assertEquals(2, source.loadTrackersCalls.get())
        assertEquals(2, source.loadGroupsCalls.get())
        assertEquals(2, source.loadMapVisibilityCalls.get())
    }

    @Test
    fun runResumeWarmup_loadsCatalog() = runTest {
        val source = FakeBootstrapDataSource()
        val bootstrap = CatalogBootstrap(dataSource = source, scope = this)

        val outcome = bootstrap.runResumeWarmup()

        assertTrue(outcome.isServerAccessible)
        assertEquals(1, source.loadTrackersCalls.get())
    }

    private class FakeBootstrapDataSource : TrackerBootstrapDataSource {
        val loadTrackersCalls = AtomicInteger(0)
        val loadGroupsCalls = AtomicInteger(0)
        val loadMapVisibilityCalls = AtomicInteger(0)

        override suspend fun loadTrackers(forceRefresh: Boolean) {
            loadTrackersCalls.incrementAndGet()
        }

        override suspend fun loadGroups(forceRefresh: Boolean) {
            loadGroupsCalls.incrementAndGet()
        }

        override suspend fun loadMapVisibility(forceRefresh: Boolean) {
            loadMapVisibilityCalls.incrementAndGet()
        }
    }
}
