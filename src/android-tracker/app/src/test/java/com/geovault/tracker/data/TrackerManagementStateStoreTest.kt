package com.geovault.tracker.data

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
}
