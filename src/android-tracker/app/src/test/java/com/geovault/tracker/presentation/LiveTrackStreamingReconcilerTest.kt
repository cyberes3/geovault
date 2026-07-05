package com.geovault.tracker.presentation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.geovault.tracker.MapStreamingStartResult
import com.geovault.tracker.MapStreamingStopResult
import com.geovault.tracker.streaming.LiveStreamServicePort
import com.geovault.tracker.streaming.LiveStreamSubscriptionRepository
import com.geovault.tracker.streaming.StreamingOwner
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
        val gateway = FakeLiveStreamServicePort()
        val repository = LiveStreamSubscriptionRepository(
            appContext = app,
            servicePort = gateway,
            dispatchDebounceMs = 0L,
            scope = this,
        )
        val reconciler = LiveTrackStreamingReconciler(repository)
        reconciler.reconcile(
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            remoteSubscriptionIds = emptySet(),
            locallyRecordedTrackerId = "",
            effectiveDisplayedId = "t1",
            effectiveDisplayedName = "One",
        )
        reconciler.reconcile(
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            remoteSubscriptionIds = emptySet(),
            locallyRecordedTrackerId = "",
            effectiveDisplayedId = "t1",
            effectiveDisplayedName = "One",
        )
        reconciler.stopForegroundStreaming()
        advanceUntilIdle()
    }

    @Test
    fun reconcile_startMarksMapLeaseUntilConsumed() = runTest {
        val app: Context = ApplicationProvider.getApplicationContext()
        val gateway = FakeLiveStreamServicePort()
        val repository = LiveStreamSubscriptionRepository(
            appContext = app,
            servicePort = gateway,
            dispatchDebounceMs = 0L,
            scope = this,
        )
        val reconciler = LiveTrackStreamingReconciler(repository)

        reconciler.reconcile(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            remoteSubscriptionIds = setOf("remote"),
            locallyRecordedTrackerId = "",
            effectiveDisplayedId = "",
            effectiveDisplayedName = "",
        )
        advanceUntilIdle()

        assertEquals(listOf(setOf("remote")), gateway.startedIds)
        assertTrue(reconciler.hasMapStreamingLease())
        assertTrue(reconciler.consumeStoppedMapStreamingLease())
        assertFalse(reconciler.hasMapStreamingLease())
        assertFalse(reconciler.consumeStoppedMapStreamingLease())
    }

    @Test
    fun reconcile_stopClearsMapLeaseWithoutConsume() = runTest {
        val app: Context = ApplicationProvider.getApplicationContext()
        val gateway = FakeLiveStreamServicePort()
        val repository = LiveStreamSubscriptionRepository(
            appContext = app,
            servicePort = gateway,
            dispatchDebounceMs = 0L,
            scope = this,
        )
        val reconciler = LiveTrackStreamingReconciler(repository)
        reconciler.reconcile(
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            remoteSubscriptionIds = setOf("remote"),
            locallyRecordedTrackerId = "",
            effectiveDisplayedId = "",
            effectiveDisplayedName = "",
        )
        advanceUntilIdle()

        reconciler.reconcile(
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            remoteSubscriptionIds = emptySet(),
            locallyRecordedTrackerId = "",
            effectiveDisplayedId = "",
            effectiveDisplayedName = "",
        )
        advanceUntilIdle()

        assertEquals(1, gateway.stopCount)
        assertFalse(reconciler.hasMapStreamingLease())
        assertFalse(reconciler.consumeStoppedMapStreamingLease())
    }

    @Test
    fun reconcile_doesNotStreamRuntimeSelectedTracker() = runTest {
        val app: Context = ApplicationProvider.getApplicationContext()
        val gateway = FakeLiveStreamServicePort()
        val repository = LiveStreamSubscriptionRepository(
            appContext = app,
            servicePort = gateway,
            dispatchDebounceMs = 0L,
            scope = this,
        )
        val reconciler = LiveTrackStreamingReconciler(repository)

        reconciler.reconcile(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            remoteSubscriptionIds = emptySet(),
            locallyRecordedTrackerId = "",
            effectiveDisplayedId = "selected",
            effectiveDisplayedName = "Selected",
        )
        advanceUntilIdle()

        assertEquals(emptyList<Set<String>>(), gateway.startedIds)
    }

    private class FakeLiveStreamServicePort : LiveStreamServicePort {
        val startedIds = mutableListOf<Set<String>>()
        var stopCount = 0

        override fun startStreaming(
            context: Context,
            trackerIds: Set<String>,
            trackerName: String?,
        ): MapStreamingStartResult {
            startedIds += trackerIds
            return MapStreamingStartResult.Started(trackerIds)
        }

        override fun stopStreaming(context: Context): MapStreamingStopResult {
            stopCount++
            return MapStreamingStopResult.Stopped
        }

        override fun persistedTargets(context: Context): Pair<Set<String>, String?> {
            return emptySet<String>() to null
        }
    }
}
