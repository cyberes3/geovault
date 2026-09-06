package com.geovault.tracker.data

import com.geovault.tracker.RepositoryResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TrackerSessionWarmupTest {

    @Test
    fun runLaunchWarmup_delegatesToOrchestrator() = runTest {
        val source = FakeBootstrapDataSource()
        val orchestrator = TrackerBootstrapOrchestrator(dataSource = source, scope = this)
        val session = TrackerSessionWarmup(orchestrator)

        val outcome = session.runLaunchWarmup()

        assertTrue(outcome.isServerAccessible)
        assertEquals(1, source.loadTrackersCalls.get())
        assertEquals(1, source.loadGroupsCalls.get())
        assertEquals(1, source.loadMapVisibilityCalls.get())
    }

    @Test
    fun resetForSignedOutSession_thenRunLaunchWarmup_loadsAgain() = runTest {
        val source = FakeBootstrapDataSource()
        val orchestrator = TrackerBootstrapOrchestrator(dataSource = source, scope = this)
        val session = TrackerSessionWarmup(orchestrator)

        session.runLaunchWarmup()
        assertEquals(1, source.loadTrackersCalls.get())

        session.resetForSignedOutSession()
        session.runLaunchWarmup()

        assertEquals(2, source.loadTrackersCalls.get())
        assertEquals(2, source.loadGroupsCalls.get())
        assertEquals(2, source.loadMapVisibilityCalls.get())
    }

    @Test
    fun runResumeWarmup_delegatesToOrchestrator() = runTest {
        val source = FakeBootstrapDataSource()
        val orchestrator = TrackerBootstrapOrchestrator(dataSource = source, scope = this)
        val session = TrackerSessionWarmup(orchestrator)

        val outcome = session.runResumeWarmup()

        assertTrue(outcome.isServerAccessible)
        assertEquals(1, source.loadTrackersCalls.get())
    }

    private class FakeBootstrapDataSource : TrackerBootstrapDataSource {
        val loadTrackersCalls = AtomicInteger(0)
        val loadGroupsCalls = AtomicInteger(0)
        val loadMapVisibilityCalls = AtomicInteger(0)

        override suspend fun loadTrackers(forceRefresh: Boolean): RepositoryResult<*> {
            loadTrackersCalls.incrementAndGet()
            return RepositoryResult.Success(Unit)
        }

        override suspend fun loadGroups(forceRefresh: Boolean): RepositoryResult<*> {
            loadGroupsCalls.incrementAndGet()
            return RepositoryResult.Success(Unit)
        }

        override suspend fun loadMapVisibility(forceRefresh: Boolean): RepositoryResult<*> {
            loadMapVisibilityCalls.incrementAndGet()
            return RepositoryResult.Success(Unit)
        }
    }
}
