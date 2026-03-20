package com.geovault.tracker.startup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.geovault.tracker.Group
import com.geovault.tracker.Tracker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class StartupRefreshOrchestratorTest {
    @Test
    fun run_callsEachStartupFetchExactlyOnce_whenSelectedTrackerExists() = runBlocking {
        val gateway = FakeStartupRefreshGateway(
            trackers = listOf(
                Tracker(id = "t1", name = "Tracker 1", color = null),
                Tracker(id = "t2", name = "Tracker 2", color = null)
            )
        )
        val orchestrator = StartupRefreshOrchestrator(gateway)
        val context = ApplicationProvider.getApplicationContext<Context>()

        val result = orchestrator.run(
            context = context,
            input = StartupRefreshInput(selectedTrackerId = "t1", savedTab = 1)
        )

        assertTrue(result.serverAccessible)
        assertEquals("t1", result.selectedTrackerForMap?.id)
        assertEquals(1, gateway.fetchUserStatusCalls)
        assertEquals(1, gateway.fetchTrackersCalls)
        assertEquals(1, gateway.fetchGroupsCalls)
        assertEquals(1, gateway.fetchMapVisibilityCalls)
        assertEquals(1, gateway.fetchAvailableToAddCalls)
        assertEquals(1, gateway.fetchSelectedTrackerCalls)
        assertEquals(1, gateway.refreshSelectedTrackerGeometryCalls)
    }

    @Test
    fun run_skipsSelectedTrackerCalls_whenNoSelectedTracker() = runBlocking {
        val gateway = FakeStartupRefreshGateway(
            trackers = listOf(Tracker(id = "t1", name = "Tracker 1", color = null))
        )
        val orchestrator = StartupRefreshOrchestrator(gateway)
        val context = ApplicationProvider.getApplicationContext<Context>()

        val result = orchestrator.run(
            context = context,
            input = StartupRefreshInput(selectedTrackerId = "", savedTab = 1)
        )

        assertTrue(result.serverAccessible)
        assertNull(result.selectedTrackerForMap)
        assertEquals(1, gateway.fetchUserStatusCalls)
        assertEquals(1, gateway.fetchTrackersCalls)
        assertEquals(1, gateway.fetchGroupsCalls)
        assertEquals(1, gateway.fetchMapVisibilityCalls)
        assertEquals(1, gateway.fetchAvailableToAddCalls)
        assertEquals(0, gateway.fetchSelectedTrackerCalls)
        assertEquals(0, gateway.refreshSelectedTrackerGeometryCalls)
    }

    @Test
    fun run_marksServerInaccessible_whenTrackersRequestFails() = runBlocking {
        val gateway = FakeStartupRefreshGateway(trackers = null)
        val orchestrator = StartupRefreshOrchestrator(gateway)
        val context = ApplicationProvider.getApplicationContext<Context>()

        val result = orchestrator.run(
            context = context,
            input = StartupRefreshInput(selectedTrackerId = "t1", savedTab = 0)
        )

        assertFalse(result.serverAccessible)
        assertNull(result.selectedTrackerForMap)
        assertEquals(1, gateway.fetchUserStatusCalls)
        assertEquals(1, gateway.fetchTrackersCalls)
        assertEquals(1, gateway.fetchGroupsCalls)
        assertEquals(1, gateway.fetchMapVisibilityCalls)
        assertEquals(1, gateway.fetchAvailableToAddCalls)
        assertEquals(1, gateway.fetchSelectedTrackerCalls)
        assertEquals(1, gateway.refreshSelectedTrackerGeometryCalls)
    }

    private class FakeStartupRefreshGateway(
        private val trackers: List<Tracker>?
    ) : StartupRefreshGateway {
        var fetchUserStatusCalls = 0
        var fetchTrackersCalls = 0
        var fetchGroupsCalls = 0
        var fetchMapVisibilityCalls = 0
        var fetchAvailableToAddCalls = 0
        var fetchSelectedTrackerCalls = 0
        var refreshSelectedTrackerGeometryCalls = 0

        override suspend fun fetchUserStatus(context: Context): String? {
            fetchUserStatusCalls++
            return "user@example.com"
        }

        override suspend fun fetchTrackers(context: Context, forceRefresh: Boolean): List<Tracker>? {
            fetchTrackersCalls++
            return trackers
        }

        override suspend fun fetchGroups(context: Context, forceRefresh: Boolean): List<Group>? {
            fetchGroupsCalls++
            return emptyList()
        }

        override suspend fun fetchSelectedTracker(context: Context, trackerId: String): Tracker? {
            fetchSelectedTrackerCalls++
            return trackers?.find { it.id == trackerId }
        }

        override suspend fun refreshSelectedTrackerGeometry(
            context: Context,
            trackerId: String,
            allData: Boolean
        ): Tracker? {
            refreshSelectedTrackerGeometryCalls++
            return trackers?.find { it.id == trackerId }
        }

        override suspend fun fetchMapVisibility(context: Context, forceRefresh: Boolean) {
            fetchMapVisibilityCalls++
        }

        override suspend fun fetchAvailableToAdd(context: Context, forceRefresh: Boolean) {
            fetchAvailableToAddCalls++
        }
    }
}
