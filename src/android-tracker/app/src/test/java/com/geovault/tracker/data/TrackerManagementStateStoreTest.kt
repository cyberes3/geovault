package com.geovault.tracker.data

import com.geovault.tracker.Group
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.Tracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrackerManagementStateStoreTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun publishHistoryCleared_emitsHistoryClearedEvent() = runTest {
        val store = TrackerManagementStateStore()
        val expectedId = "tracker-1"
        val eventDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1_000L) {
                store.events.first { it is TrackerManagementEvent.HistoryCleared }
            }
        }

        store.publishHistoryCleared(expectedId)
        advanceUntilIdle()

        val event = eventDeferred.await()
        val cleared = event as TrackerManagementEvent.HistoryCleared
        assertEquals(expectedId, cleared.trackerId)
    }

    @Test
    fun publishTracker_updatesCachedStateAndEmitsUpsert() = runTest {
        val store = TrackerManagementStateStore()
        val tracker = Tracker(id = "t-1", name = "Alpha", color = "#000000")
        val eventDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1_000L) {
                store.events.first { it is TrackerManagementEvent.TrackerUpserted }
            }
        }

        store.publishTracker(tracker)
        advanceUntilIdle()

        assertEquals(listOf("t-1"), store.trackers.value.map { it.id })
        val event = eventDeferred.await() as TrackerManagementEvent.TrackerUpserted
        assertEquals("t-1", event.tracker.id)
    }

    @Test
    fun publishGroupAndMapVisibility_updatesSharedState() {
        val store = TrackerManagementStateStore()
        val group = Group(id = "g-1", name = "Group One")
        val visibility = MapVisibilityResponse(hidden_track_ids = listOf("t-1"), hidden_group_ids = listOf("g-1"))

        store.publishGroup(group)
        store.publishMapVisibility(visibility)

        assertTrue(store.groups.value.any { it.id == "g-1" })
        assertEquals(listOf("g-1"), store.mapVisibility.value?.hidden_group_ids)
    }
}
