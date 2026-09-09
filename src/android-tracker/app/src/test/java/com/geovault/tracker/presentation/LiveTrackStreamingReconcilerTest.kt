package com.geovault.tracker.presentation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.geovault.tracker.map.MapSessionEngine
import com.geovault.tracker.streaming.FakeLiveStreamHostPort
import com.geovault.tracker.streaming.FakeLiveStreamPersistPort
import com.geovault.tracker.streaming.LiveStreamSubscriptionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LiveTrackStreamingReconcilerTest {

    @Test
    fun reconcile_smokeServicePipeline() = runTest {
        val app: Context = ApplicationProvider.getApplicationContext()
        val persist = FakeLiveStreamPersistPort()
        val gateway = FakeLiveStreamHostPort(persist)
        val repository = LiveStreamSubscriptionRepository(
            appContext = app,
            persist = persist,
            host = gateway,
            dispatchDebounceMs = 0L,
            scope = this,
        )
        MapSessionEngine.applyMapLease(
            repository,
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            remoteSubscriptionIds = emptySet(),
            locallyRecordedTrackerId = "",
            effectiveDisplayedId = "t1",
            effectiveDisplayedName = "One",
        )
        MapSessionEngine.applyMapLease(
            repository,
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            remoteSubscriptionIds = emptySet(),
            locallyRecordedTrackerId = "",
            effectiveDisplayedId = "t1",
            effectiveDisplayedName = "One",
        )
        MapSessionEngine.stopForegroundStreaming(repository)
        advanceUntilIdle()
    }

    @Test
    fun reconcile_startMarksMapLeaseUntilConsumed() = runTest {
        val app: Context = ApplicationProvider.getApplicationContext()
        val persist = FakeLiveStreamPersistPort()
        val gateway = FakeLiveStreamHostPort(persist)
        val repository = LiveStreamSubscriptionRepository(
            appContext = app,
            persist = persist,
            host = gateway,
            dispatchDebounceMs = 0L,
            scope = this,
        )
        MapSessionEngine.applyMapLease(
            repository,
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            remoteSubscriptionIds = setOf("remote"),
            locallyRecordedTrackerId = "",
            effectiveDisplayedId = "",
            effectiveDisplayedName = "",
        )
        advanceUntilIdle()

        assertEquals(listOf(setOf("remote")), gateway.startedIds)
        assertTrue(MapSessionEngine.hasMapStreamingLease(repository))
        assertTrue(MapSessionEngine.consumeStoppedMapStreamingLease(repository))
        assertFalse(MapSessionEngine.hasMapStreamingLease(repository))
        assertFalse(MapSessionEngine.consumeStoppedMapStreamingLease(repository))
    }

    @Test
    fun reconcile_stopClearsMapLeaseWithoutConsume() = runTest {
        val app: Context = ApplicationProvider.getApplicationContext()
        val persist = FakeLiveStreamPersistPort()
        val gateway = FakeLiveStreamHostPort(persist)
        val repository = LiveStreamSubscriptionRepository(
            appContext = app,
            persist = persist,
            host = gateway,
            dispatchDebounceMs = 0L,
            scope = this,
        )
        MapSessionEngine.applyMapLease(
            repository,
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            remoteSubscriptionIds = setOf("remote"),
            locallyRecordedTrackerId = "",
            effectiveDisplayedId = "",
            effectiveDisplayedName = "",
        )
        advanceUntilIdle()

        MapSessionEngine.applyMapLease(
            repository,
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            remoteSubscriptionIds = emptySet(),
            locallyRecordedTrackerId = "",
            effectiveDisplayedId = "",
            effectiveDisplayedName = "",
        )
        advanceUntilIdle()

        assertEquals(1, gateway.stopCount)
        assertFalse(MapSessionEngine.hasMapStreamingLease(repository))
        assertFalse(MapSessionEngine.consumeStoppedMapStreamingLease(repository))
    }

    @Test
    fun reconcile_doesNotStreamRuntimeSelectedTracker() = runTest {
        val app: Context = ApplicationProvider.getApplicationContext()
        val persist = FakeLiveStreamPersistPort()
        val gateway = FakeLiveStreamHostPort(persist)
        val repository = LiveStreamSubscriptionRepository(
            appContext = app,
            persist = persist,
            host = gateway,
            dispatchDebounceMs = 0L,
            scope = this,
        )
        MapSessionEngine.applyMapLease(
            repository,
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            remoteSubscriptionIds = emptySet(),
            locallyRecordedTrackerId = "",
            effectiveDisplayedId = "selected",
            effectiveDisplayedName = "Selected",
        )
        advanceUntilIdle()

        assertEquals(emptyList<Set<String>>(), gateway.startedIds)
    }

}
