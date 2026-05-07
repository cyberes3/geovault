package com.geovault.tracker.presentation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.geovault.tracker.MapStreamingStartResult
import com.geovault.tracker.MapStreamingStopResult
import com.geovault.tracker.services.LiveStreamRuntimeSnapshot
import com.geovault.tracker.services.LiveStreamRuntimeStateStore
import com.geovault.tracker.services.StreamingHealth
import com.geovault.tracker.services.StreamingIntent
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
class LiveTrackStreamingTargetCoordinatorTest {

    @After
    fun tearDown() {
        LiveTrackStreamingTargetCoordinator.resetForTests()
        LiveStreamRuntimeStateStore.update { LiveStreamRuntimeSnapshot() }
    }

    @Test
    fun resolveSubscriptionPlan_unionsOwnersAndRemovesLocalRecorder() {
        val plan = LiveTrackStreamingTargetCoordinator.resolveSubscriptionPlan(
            StreamingLeaseSet(
                mapRequest = LiveTrackStreamingTargetRequest(
                    trackerIds = setOf("local", "remote-a"),
                    trackerName = null,
                    locallyRecordedTrackerId = "local",
                ),
                paramsRequest = LiveTrackStreamingTargetRequest(
                    trackerIds = setOf("remote-b", " "),
                    trackerName = null,
                    locallyRecordedTrackerId = null,
                ),
            )
        )

        assertEquals(setOf("remote-a", "remote-b"), plan.trackerIds)
        assertEquals(null, plan.trackerName)
        assertTrue(plan.shouldRunService)
    }

    @Test
    fun resolveSubscriptionPlan_singleTargetKeepsName() {
        val plan = LiveTrackStreamingTargetCoordinator.resolveSubscriptionPlan(
            StreamingLeaseSet(
                mapRequest = LiveTrackStreamingTargetRequest(
                    trackerIds = setOf("remote"),
                    trackerName = "Remote Tracker",
                    locallyRecordedTrackerId = null,
                ),
                paramsRequest = null,
            )
        )

        assertEquals(setOf("remote"), plan.trackerIds)
        assertEquals("Remote Tracker", plan.trackerName)
    }

    @Test
    fun resolveSubscriptionPlan_onlyLocalRecorderStopsService() {
        val plan = LiveTrackStreamingTargetCoordinator.resolveSubscriptionPlan(
            StreamingLeaseSet(
                mapRequest = LiveTrackStreamingTargetRequest(
                    trackerIds = setOf("local"),
                    trackerName = "Local",
                    locallyRecordedTrackerId = "local",
                ),
                paramsRequest = null,
            )
        )

        assertEquals(emptySet<String>(), plan.trackerIds)
        assertFalse(plan.shouldRunService)
    }

    @Test
    fun resolveSubscriptionPlan_excludesLocallyRecordedAcrossOwners() {
        // STREAMING EXCLUSION: a locally-recorded id declared by ANY owner removes that tracker
        // from the merged plan so we never round-trip the actively-recorded GPS feed through the
        // websocket. The other owner's request can list the same id; the union is filtered.
        val plan = LiveTrackStreamingTargetCoordinator.resolveSubscriptionPlan(
            StreamingLeaseSet(
                mapRequest = LiveTrackStreamingTargetRequest(
                    trackerIds = setOf("local", "remote-a"),
                    trackerName = null,
                    locallyRecordedTrackerId = "local",
                ),
                paramsRequest = LiveTrackStreamingTargetRequest(
                    trackerIds = setOf("local", "remote-b"),
                    trackerName = null,
                    locallyRecordedTrackerId = null,
                ),
            )
        )

        assertEquals(setOf("remote-a", "remote-b"), plan.trackerIds)
    }

    @Test
    fun replaceRequest_startFailureStopsPreviousStreamAndMarksRuntimeFailed() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val gateway = FakeLiveTrackStreamingServiceGateway(
            startResults = ArrayDeque(
                listOf(
                    MapStreamingStartResult.Started(setOf("old")),
                    MapStreamingStartResult.Failed("blocked"),
                )
            )
        )
        LiveTrackStreamingTargetCoordinator.resetForTests(gateway)
        LiveStreamRuntimeStateStore.update {
            it.copy(
                intent = StreamingIntent.Wanted(setOf("old")),
                health = StreamingHealth.Running,
                activeTrackerIds = setOf("old"),
            )
        }

        LiveTrackStreamingTargetCoordinator.replaceRequest(
            context = context,
            owner = LiveTrackStreamingOwner.Map,
            request = LiveTrackStreamingTargetRequest(
                trackerIds = setOf("old"),
                trackerName = "Old",
                locallyRecordedTrackerId = null,
            ),
        )
        val failed = LiveTrackStreamingTargetCoordinator.replaceRequest(
            context = context,
            owner = LiveTrackStreamingOwner.Map,
            request = LiveTrackStreamingTargetRequest(
                trackerIds = setOf("new"),
                trackerName = "New",
                locallyRecordedTrackerId = null,
            ),
        )

        assertTrue(failed is StreamingSubscriptionApplyResult.Failed)
        assertEquals(1, gateway.stopCount)
        val snapshot = LiveStreamRuntimeStateStore.state.value
        // Stop succeeded by default, so the coordinator collapses to Idle/Stopped with empty
        // active set and the failure reason carried forward for UX.
        assertFalse(snapshot.wantsSubscription)
        assertEquals(StreamingIntent.Idle, snapshot.intent)
        assertEquals(StreamingHealth.Stopped, snapshot.health)
        assertEquals(emptySet<String>(), snapshot.activeTrackerIds)
        assertEquals("blocked", snapshot.failureReason)
    }

    @Test
    fun replaceRequest_startFailureClearsDedupeSoSameRequestCanRetry() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val gateway = FakeLiveTrackStreamingServiceGateway(
            startResults = ArrayDeque(
                listOf(
                    MapStreamingStartResult.Failed("first"),
                    MapStreamingStartResult.Started(setOf("new")),
                )
            )
        )
        LiveTrackStreamingTargetCoordinator.resetForTests(gateway)
        val request = LiveTrackStreamingTargetRequest(
            trackerIds = setOf("new"),
            trackerName = "New",
            locallyRecordedTrackerId = null,
        )

        val failed = LiveTrackStreamingTargetCoordinator.replaceRequest(
            context = context,
            owner = LiveTrackStreamingOwner.Map,
            request = request,
        )
        val retried = LiveTrackStreamingTargetCoordinator.replaceRequest(
            context = context,
            owner = LiveTrackStreamingOwner.Map,
            request = request,
        )

        assertTrue(failed is StreamingSubscriptionApplyResult.Failed)
        assertTrue(retried is StreamingSubscriptionApplyResult.Applied)
        assertEquals(listOf(setOf("new"), setOf("new")), gateway.startedIds)
    }

    @Test
    fun replaceRequest_stopFailureDoesNotMarkEmptyPlanApplied() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val gateway = FakeLiveTrackStreamingServiceGateway(
            startResults = ArrayDeque(),
            stopResults = ArrayDeque(listOf(MapStreamingStopResult.Failed("stop blocked")))
        )
        LiveTrackStreamingTargetCoordinator.resetForTests(gateway)
        LiveStreamRuntimeStateStore.update {
            it.copy(
                intent = StreamingIntent.Wanted(setOf("old")),
                health = StreamingHealth.Running,
                activeTrackerIds = setOf("old"),
            )
        }

        val failed = LiveTrackStreamingTargetCoordinator.replaceRequest(
            context = context,
            owner = LiveTrackStreamingOwner.Map,
            request = null,
        )

        assertTrue(failed is StreamingSubscriptionApplyResult.Failed)
        assertEquals(1, gateway.stopCount)
        val snapshot = LiveStreamRuntimeStateStore.state.value
        // Stop request failed -> intent is Idle (we wanted to stop) but health is FailedTransient
        // so reconcile can attempt cleanup again on the next tick.
        assertEquals(StreamingIntent.Idle, snapshot.intent)
        assertEquals(StreamingHealth.FailedTransient, snapshot.health)
        assertEquals("stop blocked", snapshot.failureReason)
    }

    @Test
    fun replaceRequest_startFailureIncludesStopFailureReason() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val gateway = FakeLiveTrackStreamingServiceGateway(
            startResults = ArrayDeque(listOf(MapStreamingStartResult.Failed("start blocked"))),
            stopResults = ArrayDeque(listOf(MapStreamingStopResult.Failed("stop blocked")))
        )
        LiveTrackStreamingTargetCoordinator.resetForTests(gateway)
        LiveStreamRuntimeStateStore.update {
            it.copy(
                intent = StreamingIntent.Wanted(setOf("old")),
                health = StreamingHealth.Running,
                activeTrackerIds = setOf("old"),
            )
        }

        val failed = LiveTrackStreamingTargetCoordinator.replaceRequest(
            context = context,
            owner = LiveTrackStreamingOwner.Map,
            request = LiveTrackStreamingTargetRequest(
                trackerIds = setOf("new"),
                trackerName = "New",
                locallyRecordedTrackerId = null,
            ),
        )

        assertTrue(failed is StreamingSubscriptionApplyResult.Failed)
        assertEquals(1, gateway.stopCount)
        val snapshot = LiveStreamRuntimeStateStore.state.value
        // Start failed AND the cleanup stop also failed -> we keep the prior Wanted intent (we
        // are still trying to subscribe conceptually) but health is FailedTransient and the
        // active set is preserved so callers can decide whether to retry or surface an error.
        assertEquals(StreamingHealth.FailedTransient, snapshot.health)
        assertTrue(snapshot.wantsSubscription)
        assertEquals(setOf("old"), snapshot.activeTrackerIds)
        assertEquals("start blocked; stop_failed:stop blocked", snapshot.failureReason)
    }

    private class FakeLiveTrackStreamingServiceGateway(
        private val startResults: ArrayDeque<MapStreamingStartResult>,
        private val stopResults: ArrayDeque<MapStreamingStopResult> = ArrayDeque()
    ) : LiveTrackStreamingServiceGateway {
        val startedIds = mutableListOf<Set<String>>()
        var stopCount = 0

        override fun startStreaming(
            context: Context,
            trackerIds: Set<String>,
            trackerName: String?
        ): MapStreamingStartResult {
            startedIds += trackerIds
            return startResults.removeFirst()
        }

        override fun stopStreaming(context: Context): MapStreamingStopResult {
            stopCount++
            return stopResults.removeFirstOrNull() ?: MapStreamingStopResult.Stopped
        }
    }
}
