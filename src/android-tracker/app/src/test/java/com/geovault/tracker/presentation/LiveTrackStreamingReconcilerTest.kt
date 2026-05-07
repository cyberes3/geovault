package com.geovault.tracker.presentation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.geovault.tracker.MapStreamingStartResult
import com.geovault.tracker.MapStreamingStopResult
import com.geovault.tracker.services.LiveStreamRuntimeSnapshot
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LiveTrackStreamingReconcilerTest {

    @After
    fun tearDown() {
        LiveTrackStreamingTargetCoordinator.resetForTests()
    }

    @Test
    fun reconcile_smokeServicePipeline() = runBlocking {
        // COMBINED-RECONCILE: invalidateDedupe is gone — back-to-back identical reconciles are
        // absorbed by the coordinator's `lastAppliedIds` gate. We only smoke the pipeline here to
        // make sure repeated reconcile + stopForegroundStreaming calls don't crash.
        val app: Context = ApplicationProvider.getApplicationContext()
        val reconciler = LiveTrackStreamingReconciler(app)
        val state = TrackerMapUiState(
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            streamTargetIds = emptySet(),
            runtime = TrackingRuntimeSnapshot(selectedTrackerId = "t1", isRunning = false),
        )
        reconciler.reconcile(
            state = state,
            effectiveDisplayedId = "t1",
            effectiveDisplayedName = "One",
            streamRuntime = LiveStreamRuntimeSnapshot(),
        )
        reconciler.reconcile(
            state = state,
            effectiveDisplayedId = "t1",
            effectiveDisplayedName = "One",
            streamRuntime = LiveStreamRuntimeSnapshot(),
        )
        reconciler.stopForegroundStreaming()
    }

    @Test
    fun reconcile_startMarksMapLeaseUntilConsumed() {
        val app: Context = ApplicationProvider.getApplicationContext()
        val gateway = FakeLiveTrackStreamingServiceGateway()
        LiveTrackStreamingTargetCoordinator.resetForTests(gateway)
        val reconciler = LiveTrackStreamingReconciler(app)

        reconciler.reconcile(
            state = TrackerMapUiState(
                mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
                streamTargetIds = setOf("remote"),
                runtime = TrackingRuntimeSnapshot(selectedTrackerId = "selected"),
            ),
            effectiveDisplayedId = "",
            effectiveDisplayedName = "",
            streamRuntime = LiveStreamRuntimeSnapshot(),
        )

        assertEquals(listOf(setOf("remote")), gateway.startedIds)
        assertTrue(reconciler.hasMapStreamingLease())
        assertTrue(reconciler.consumeStoppedMapStreamingLease())
        assertFalse(reconciler.hasMapStreamingLease())
        assertFalse(reconciler.consumeStoppedMapStreamingLease())
    }

    @Test
    fun reconcile_stopClearsMapLeaseWithoutConsume() {
        val app: Context = ApplicationProvider.getApplicationContext()
        val gateway = FakeLiveTrackStreamingServiceGateway()
        LiveTrackStreamingTargetCoordinator.resetForTests(gateway)
        val reconciler = LiveTrackStreamingReconciler(app)
        reconciler.reconcile(
            state = TrackerMapUiState(
                mode = TrackerMapDisplayMode.ALL_QUEUE,
                streamTargetIds = setOf("remote"),
                runtime = TrackingRuntimeSnapshot(selectedTrackerId = "selected"),
            ),
            effectiveDisplayedId = "",
            effectiveDisplayedName = "",
            streamRuntime = LiveStreamRuntimeSnapshot(),
        )

        reconciler.reconcile(
            state = TrackerMapUiState(
                mode = TrackerMapDisplayMode.ALL_QUEUE,
                streamTargetIds = emptySet(),
                runtime = TrackingRuntimeSnapshot(selectedTrackerId = "selected"),
            ),
            effectiveDisplayedId = "",
            effectiveDisplayedName = "",
            streamRuntime = LiveStreamRuntimeSnapshot(),
        )

        assertEquals(1, gateway.stopCount)
        assertFalse(reconciler.hasMapStreamingLease())
        assertFalse(reconciler.consumeStoppedMapStreamingLease())
    }

    @Test
    fun reconcile_doesNotStreamRuntimeSelectedTracker() {
        val app: Context = ApplicationProvider.getApplicationContext()
        val gateway = FakeLiveTrackStreamingServiceGateway()
        LiveTrackStreamingTargetCoordinator.resetForTests(gateway)
        val reconciler = LiveTrackStreamingReconciler(app)

        reconciler.reconcile(
            state = TrackerMapUiState(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                runtime = TrackingRuntimeSnapshot(selectedTrackerId = "selected"),
            ),
            effectiveDisplayedId = "selected",
            effectiveDisplayedName = "Selected",
            streamRuntime = LiveStreamRuntimeSnapshot(),
        )

        assertEquals(emptyList<Set<String>>(), gateway.startedIds)
    }

    private class FakeLiveTrackStreamingServiceGateway : LiveTrackStreamingServiceGateway {
        val startedIds = mutableListOf<Set<String>>()
        var stopCount = 0

        override fun startStreaming(
            context: Context,
            trackerIds: Set<String>,
            trackerName: String?
        ): MapStreamingStartResult {
            startedIds += trackerIds
            return MapStreamingStartResult.Started(trackerIds)
        }

        override fun stopStreaming(context: Context): MapStreamingStopResult {
            stopCount++
            return MapStreamingStopResult.Stopped
        }
    }
}
