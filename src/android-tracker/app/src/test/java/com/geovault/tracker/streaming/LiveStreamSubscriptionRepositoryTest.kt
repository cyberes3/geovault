package com.geovault.tracker.streaming

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.geovault.tracker.MapStreamingStartResult
import com.geovault.tracker.MapStreamingStopResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [LiveStreamSubscriptionRepository] is the single source of truth this whole streaming audit
 * was built around, and it previously had zero dedicated coverage even though it owns exactly
 * the behaviors implicated in the "streamed tracker not updating" production bug: lease
 * merging, the apply-dedupe gate, and [requestReapply]'s ability to force a redispatch past that
 * gate for the liveness watchdog.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LiveStreamSubscriptionRepositoryTest {

    private fun newRepository(
        gateway: FakeLiveStreamServicePort,
        scope: kotlinx.coroutines.CoroutineScope,
        dispatchDebounceMs: Long = 0L,
        bootstrapGraceMs: Long = StreamingConfig.bootstrapGraceMs,
        elapsedRealtimeMs: () -> Long = { 0L },
    ): LiveStreamSubscriptionRepository {
        val app: Context = ApplicationProvider.getApplicationContext()
        return LiveStreamSubscriptionRepository(
            appContext = app,
            servicePort = gateway,
            elapsedRealtimeMs = elapsedRealtimeMs,
            dispatchDebounceMs = dispatchDebounceMs,
            bootstrapGraceMs = bootstrapGraceMs,
            scope = scope,
        )
    }

    @Test
    fun setLease_mergesUnionOfOwnersAndExcludesLocallyRecordedIds() = runTest {
        val gateway = FakeLiveStreamServicePort()
        val repository = newRepository(gateway, this)

        repository.setLease(StreamingOwner.MAP, OwnerLease(trackerIds = setOf("a", "b")))
        repository.setLease(
            StreamingOwner.PARAMS,
            OwnerLease(trackerIds = setOf("b", "c"), locallyRecordedTrackerId = "c"),
        )
        advanceUntilIdle()

        // "c" is excluded from the dispatched set because PARAMS declared it as locally
        // recorded -- the whole point of per-lease `locallyRecordedTrackerId` is that the
        // locally-recorded tracker never round-trips through the websocket regardless of
        // which owner's request happened to include it.
        assertEquals(listOf(setOf("a", "b")), gateway.startedIds)
    }

    @Test
    fun setLease_sameLeaseValueIsNoOpAndDoesNotRedispatch() = runTest {
        val gateway = FakeLiveStreamServicePort()
        val repository = newRepository(gateway, this)
        val lease = OwnerLease(trackerIds = setOf("a"))

        repository.setLease(StreamingOwner.MAP, lease)
        advanceUntilIdle()
        assertEquals(1, gateway.startedIds.size)

        repository.setLease(StreamingOwner.MAP, lease)
        advanceUntilIdle()

        assertEquals(1, gateway.startedIds.size)
    }

    @Test
    fun setLease_droppingToEmptyDispatchesStop() = runTest {
        val gateway = FakeLiveStreamServicePort()
        val repository = newRepository(gateway, this)

        repository.setLease(StreamingOwner.MAP, OwnerLease(trackerIds = setOf("a")))
        advanceUntilIdle()
        assertEquals(0, gateway.stopCount)

        repository.setLease(StreamingOwner.MAP, null)
        advanceUntilIdle()

        assertEquals(1, gateway.stopCount)
    }

    @Test
    fun requestReapply_forcesRedispatchOfIdenticalTargets() = runTest {
        // This is the exact mechanism the liveness watchdog relies on: a session that looks
        // unchanged (same tracker ids) but has gone silent must still be force-reconnected.
        // Without `requestReapply` clearing the dedupe gate, `dispatch()`'s
        // `hasApplied && ids == lastAppliedIds` short-circuit would make a watchdog-triggered
        // reconnect attempt silently do nothing.
        val gateway = FakeLiveStreamServicePort()
        val repository = newRepository(gateway, this)

        repository.setLease(StreamingOwner.MAP, OwnerLease(trackerIds = setOf("a")))
        advanceUntilIdle()
        assertEquals(1, gateway.startedIds.size)

        repository.requestReapply(ReapplyReason.STALE_CONNECTION)
        advanceUntilIdle()

        assertEquals(2, gateway.startedIds.size)
        assertEquals(setOf("a"), gateway.startedIds.last())
    }

    @Test
    fun clearAllLeases_unconditionallyDispatchesStopEvenWithNoActiveLease() = runTest {
        val gateway = FakeLiveStreamServicePort()
        val repository = newRepository(gateway, this)

        repository.clearAllLeases(ClearReason.LOGOUT)
        advanceUntilIdle()

        assertEquals(1, gateway.stopCount)
    }

    @Test
    fun clearAllLeases_clearsLeaseMapSoASubsequentIdenticalLeaseRedispatches() = runTest {
        val gateway = FakeLiveStreamServicePort()
        val repository = newRepository(gateway, this)
        val lease = OwnerLease(trackerIds = setOf("a"))

        repository.setLease(StreamingOwner.MAP, lease)
        advanceUntilIdle()
        assertEquals(1, gateway.startedIds.size)

        repository.clearAllLeases(ClearReason.ACCOUNT_RESET)
        advanceUntilIdle()
        assertEquals(1, gateway.stopCount)

        // Re-setting the *same* lease object after a clear must dispatch again: `setLease`'s
        // "no-op if unchanged" check compares against the in-memory leases map, which
        // `clearAllLeases` emptied.
        repository.setLease(StreamingOwner.MAP, lease)
        advanceUntilIdle()
        assertEquals(2, gateway.startedIds.size)
    }

    @Test
    fun clearLeasesWithoutDispatch_neverTouchesTheServicePort() = runTest {
        val gateway = FakeLiveStreamServicePort()
        val repository = newRepository(gateway, this)

        repository.setLease(StreamingOwner.MAP, OwnerLease(trackerIds = setOf("a")))
        advanceUntilIdle()
        assertEquals(1, gateway.startedIds.size)

        repository.clearLeasesWithoutDispatch()
        advanceUntilIdle()

        // Still just the one dispatch from the original setLease -- no stop, no extra start.
        assertEquals(1, gateway.startedIds.size)
        assertEquals(0, gateway.stopCount)
        assertTrue(repository.state.value.leases.isEmpty())
    }

    @Test
    fun seedFromPersistedState_installsBootstrapLeaseAsStartingUntilARealLeaseArrives() = runTest {
        val gateway = FakeLiveStreamServicePort(persisted = setOf("restored") to "Restored")
        val repository = newRepository(gateway, this)

        repository.seedFromPersistedState()

        assertEquals(setOf("restored"), repository.state.value.mergedTargets)
        assertEquals(ConnectionPhase.STARTING, repository.state.value.connection)

        // A real lease from any owner consumes/replaces the bootstrap seed rather than merging
        // with it -- see `setLease` clearing `bootstrapLease` whenever a non-null lease lands.
        repository.setLease(StreamingOwner.MAP, OwnerLease(trackerIds = setOf("real")))
        advanceUntilIdle()

        assertEquals(setOf("real"), repository.state.value.mergedTargets)
        assertEquals(listOf(setOf("real")), gateway.startedIds)
    }

    @Test
    fun seedFromPersistedState_isNoOpWhenNothingWasPersisted() = runTest {
        val gateway = FakeLiveStreamServicePort(persisted = emptySet<String>() to null)
        val repository = newRepository(gateway, this)

        repository.seedFromPersistedState()

        assertTrue(repository.state.value.mergedTargets.isEmpty())
        assertEquals(ConnectionPhase.IDLE, repository.state.value.connection)
    }

    @Test
    fun bootstrapLease_expiresAfterGraceWindowElapsesWithNoRealLeaseClaimed() = runTest {
        var nowMs = 0L
        val gateway = FakeLiveStreamServicePort(persisted = setOf("ghost") to "Ghost")
        val repository = newRepository(
            gateway,
            this,
            bootstrapGraceMs = 5_000L,
            elapsedRealtimeMs = { nowMs },
        )

        repository.seedFromPersistedState()
        assertEquals(setOf("ghost"), repository.state.value.mergedTargets)

        // Past the grace deadline: the next dispatch tick (`requestReapply` here stands in for
        // any trigger) must expire the bootstrap lease instead of keeping a session nobody ever
        // claimed alive indefinitely.
        nowMs = 5_001L
        repository.requestReapply(ReapplyReason.MANUAL)
        advanceUntilIdle()

        assertTrue(repository.state.value.mergedTargets.isEmpty())
        assertEquals(1, gateway.stopCount)
    }

    @Test
    fun dispatchStart_failureResetsApplyGateAndRecordsFailureReason() = runTest {
        val gateway = FakeLiveStreamServicePort(startResult = { ids -> MapStreamingStartResult.Failed("boom") })
        val repository = newRepository(gateway, this)

        repository.setLease(StreamingOwner.MAP, OwnerLease(trackerIds = setOf("a")))
        advanceUntilIdle()

        assertEquals("boom", repository.state.value.failureReason)
        assertEquals(ConnectionPhase.IDLE, repository.state.value.connection)
        assertEquals(1, gateway.stopCount) // start failure triggers a cleanup stop

        // The apply gate must have been reset (hasApplied = false) so an identical subsequent
        // lease is retried rather than silently deduped against a start that never actually
        // succeeded.
        repository.requestReapply(ReapplyReason.FAILURE_RECOVERY)
        advanceUntilIdle()
        assertEquals(2, gateway.startAttempts)
    }

    @Test
    fun dispatchStop_failureSetsFailedTransientAndResetsApplyGate() = runTest {
        val gateway = FakeLiveStreamServicePort(stopResult = { MapStreamingStopResult.Failed("stop_boom") })
        val repository = newRepository(gateway, this)

        repository.setLease(StreamingOwner.MAP, OwnerLease(trackerIds = setOf("a")))
        repository.setLease(StreamingOwner.MAP, null)
        advanceUntilIdle()

        assertEquals(ConnectionPhase.FAILED_TRANSIENT, repository.state.value.connection)
        assertEquals("stop_boom", repository.state.value.failureReason)
    }

    @Test
    fun reportConnectionUpdate_isOrthogonalToLeaseState() = runTest {
        val gateway = FakeLiveStreamServicePort()
        val repository = newRepository(gateway, this)

        repository.setLease(StreamingOwner.MAP, OwnerLease(trackerIds = setOf("a")))
        advanceUntilIdle()

        repository.reportConnectionUpdate(ConnectionPhase.RECONNECTING, setOf("a"), "network_lost")

        // Wanting a subscription and the connection being unhealthy are independent axes: a
        // caller can still want the subscription while RECONNECTING.
        assertTrue(repository.state.value.wantsSubscription)
        assertFalse(repository.state.value.subscriptionHealthy)
        assertEquals("network_lost", repository.state.value.failureReason)
    }

    @Test
    fun dispatch_blocksASecondCallerRatherThanRunningConcurrentlyWithAnInFlightDispatch() {
        // Regression test for a race where `scheduleDispatch`'s `dispatchJob?.cancel()` couldn't
        // actually stop an in-flight tick (dispatch() has no suspension point to honor
        // cancellation at), so a second dispatch triggered while the first was still inside
        // `servicePort.startStreaming` -- exactly the liveness-watchdog-vs-map-ViewModel scenario
        // that happens in production -- could run concurrently with it on Dispatchers.IO's
        // thread pool, letting a stale tick clobber a fresher one. Deterministic (no sleep-based
        // guessing): the fake gateway blocks the *first* call until the test has already
        // triggered the second, so any concurrent entry is captured for certain rather than
        // relying on timing to happen to line up.
        val concurrentEntries = java.util.concurrent.atomic.AtomicInteger(0)
        val maxObservedConcurrency = java.util.concurrent.atomic.AtomicInteger(0)
        val firstCallEntered = java.util.concurrent.CountDownLatch(1)
        val releaseFirstCall = java.util.concurrent.CountDownLatch(1)
        val isFirstCall = java.util.concurrent.atomic.AtomicBoolean(true)
        val gateway = object : LiveStreamServicePort {
            override fun startStreaming(
                context: Context,
                trackerIds: Set<String>,
                trackerName: String?,
            ): MapStreamingStartResult {
                val current = concurrentEntries.incrementAndGet()
                maxObservedConcurrency.updateAndGet { prev -> maxOf(prev, current) }
                if (isFirstCall.compareAndSet(true, false)) {
                    firstCallEntered.countDown()
                    releaseFirstCall.await(2, java.util.concurrent.TimeUnit.SECONDS)
                }
                concurrentEntries.decrementAndGet()
                return MapStreamingStartResult.Started(trackerIds)
            }

            override fun stopStreaming(context: Context): MapStreamingStopResult = MapStreamingStopResult.Stopped

            override fun persistedTargets(context: Context): Pair<Set<String>, String?> = emptySet<String>() to null
        }
        val app: Context = ApplicationProvider.getApplicationContext()
        val realScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )
        val repository = LiveStreamSubscriptionRepository(
            appContext = app,
            servicePort = gateway,
            dispatchDebounceMs = 0L,
            scope = realScope,
        )

        repository.setLease(StreamingOwner.MAP, OwnerLease(trackerIds = setOf("a")))
        assertTrue(firstCallEntered.await(2, java.util.concurrent.TimeUnit.SECONDS))

        // The first dispatch is now confirmed blocked inside the gateway call. Trigger a second,
        // superseding dispatch (as the liveness watchdog would) while it's still in flight.
        repository.requestReapply(ReapplyReason.STALE_CONNECTION)
        // Give the second dispatch tick a real chance to reach the gateway if it's going to --
        // with the bug, it runs concurrently almost immediately; with the fix, it blocks on the
        // lock for the entire 2s below instead.
        Thread.sleep(200)

        releaseFirstCall.countDown()
        Thread.sleep(200)
        realScope.cancel()

        assertEquals(1, maxObservedConcurrency.get())
    }

    private class FakeLiveStreamServicePort(
        private val persisted: Pair<Set<String>, String?> = emptySet<String>() to null,
        private val startResult: (Set<String>) -> MapStreamingStartResult = { ids -> MapStreamingStartResult.Started(ids) },
        private val stopResult: () -> MapStreamingStopResult = { MapStreamingStopResult.Stopped },
    ) : LiveStreamServicePort {
        val startedIds = mutableListOf<Set<String>>()
        var startAttempts = 0
        var stopCount = 0

        override fun startStreaming(
            context: Context,
            trackerIds: Set<String>,
            trackerName: String?,
        ): MapStreamingStartResult {
            startAttempts++
            val result = startResult(trackerIds)
            if (result is MapStreamingStartResult.Started) startedIds += result.trackerIds
            return result
        }

        override fun stopStreaming(context: Context): MapStreamingStopResult {
            stopCount++
            return stopResult()
        }

        override fun persistedTargets(context: Context): Pair<Set<String>, String?> = persisted
    }
}
