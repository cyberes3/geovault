package com.geovault.tracker.streaming

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
        host: FakeLiveStreamHostPort,
        scope: kotlinx.coroutines.CoroutineScope,
        persist: FakeLiveStreamPersistPort = FakeLiveStreamPersistPort(),
        dispatchDebounceMs: Long = 0L,
        bootstrapGraceMs: Long = StreamingConfig.bootstrapGraceMs,
        elapsedRealtimeMs: () -> Long = { 0L },
    ): LiveStreamSubscriptionRepository {
        val app: Context = ApplicationProvider.getApplicationContext()
        return LiveStreamSubscriptionRepository(
            appContext = app,
            persist = persist,
            host = host,
            elapsedRealtimeMs = elapsedRealtimeMs,
            dispatchDebounceMs = dispatchDebounceMs,
            bootstrapGraceMs = bootstrapGraceMs,
            scope = scope,
        )
    }

    private fun ports(
        persisted: Pair<Set<String>, String?> = emptySet<String>() to null,
        startResult: (Set<String>) -> LiveStreamApplyResult = { ids -> LiveStreamApplyResult.Started(ids) },
        stopResult: () -> LiveStreamStopResult = { LiveStreamStopResult.Stopped },
    ): Pair<FakeLiveStreamPersistPort, FakeLiveStreamHostPort> {
        val persist = FakeLiveStreamPersistPort(persisted)
        return persist to FakeLiveStreamHostPort(persist, startResult, stopResult)
    }

    @Test
    fun setLease_mergesUnionOfOwnersAndExcludesLocallyRecordedIds() = runTest {
        val (persist, gateway) = ports()
        val repository = newRepository(gateway, this, persist)

        repository.setLease(StreamingOwner.MAP, StreamIntent(trackerIds = setOf("a", "b")))
        repository.setLease(
            StreamingOwner.PARAMS,
            StreamIntent(trackerIds = setOf("b", "c"), locallyRecordedTrackerId = "c"),
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
        val (persist, gateway) = ports()
        val repository = newRepository(gateway, this, persist)
        val lease = StreamIntent(trackerIds = setOf("a"))

        repository.setLease(StreamingOwner.MAP, lease)
        advanceUntilIdle()
        assertEquals(1, gateway.startedIds.size)

        repository.setLease(StreamingOwner.MAP, lease)
        advanceUntilIdle()

        assertEquals(1, gateway.startedIds.size)
    }

    @Test
    fun setLease_droppingToEmptyDispatchesStop() = runTest {
        val (persist, gateway) = ports()
        val repository = newRepository(gateway, this, persist)

        repository.setLease(StreamingOwner.MAP, StreamIntent(trackerIds = setOf("a")))
        advanceUntilIdle()
        assertEquals(0, gateway.stopCount)

        repository.setLease(StreamingOwner.MAP, null)
        advanceUntilIdle()

        assertEquals(1, gateway.stopCount)
    }

    @Test
    fun setLease_marksConnectionAsStartingBeforeDispatchSoTheNewLeaseIsNotMisreadAsEnded() = runTest {
        // Regression test: with a real (non-zero) debounce, `dispatch()` -- and the
        // `connection` update that comes from actually contacting the service -- doesn't run
        // until after the debounce window. A caller reading `state` synchronously right after
        // `setLease` (like `MapSessionEngine`'s stream-state collector) must not see a
        // stale `connection=IDLE` alongside `wantsSubscription=true`, since that combination is
        // indistinguishable from a session that actually ran and ended -- which previously
        // caused that just-set lease to be torn down before the service ever got a chance to
        // start it.
        val (persist, gateway) = ports()
        val repository = newRepository(gateway, this, persist, dispatchDebounceMs = 350L)

        repository.setLease(StreamingOwner.MAP, StreamIntent(trackerIds = setOf("a")))

        assertTrue(repository.state.value.wantsSubscription)
        assertFalse(repository.state.value.subscriptionEnded)
        assertEquals(ConnectionPhase.STARTING, repository.state.value.connection)

        advanceUntilIdle()
        assertEquals(listOf(setOf("a")), gateway.startedIds)
    }

    @Test
    fun setLease_neverEmitsAnIntermediateStateWhereANewLeaseLooksAlreadyEnded() = runTest {
        // Regression test for a production "streaming never starts" bug: the previous
        // implementation applied the lease-merge update and the STARTING fix-up as two
        // *separate* `_state.update` calls. A collector using plain `collect` (exactly what
        // `MapSessionEngine`'s stream-state collector does, deliberately, so it never
        // misses the real post-stop cleanup) observes every distinct emission -- including the
        // intermediate one in between those two updates, where `wantsSubscription` was already
        // true but `connection` was still the stale IDLE left over from repository construction
        // (or a prior stop). That intermediate emission is indistinguishable from a session
        // that actually ran and ended, so it immediately tore the brand new lease back down --
        // every single time, since the debounce means `dispatch()` never runs fast enough to
        // race ahead of it. This test collects *every* emission (not just the final value) and
        // asserts none of them ever show that broken combination.
        val (persist, gateway) = ports()
        val repository = newRepository(gateway, this, persist, dispatchDebounceMs = 350L)
        val observedStates = mutableListOf<LiveStreamSubscriptionState>()
        val collectorJob = launch { repository.state.collect { observedStates.add(it) } }

        repository.setLease(StreamingOwner.MAP, StreamIntent(trackerIds = setOf("a")))
        advanceUntilIdle()

        collectorJob.cancel()
        val brokenEmissions = observedStates.filter { it.wantsSubscription && it.subscriptionEnded }
        assertTrue(
            "no emission should ever report wantsSubscription=true alongside subscriptionEnded=true, " +
                "but saw: $brokenEmissions",
            brokenEmissions.isEmpty(),
        )
    }

    @Test
    fun requestReapply_forcesRedispatchOfIdenticalTargets() = runTest {
        // This is the exact mechanism the liveness watchdog relies on: a session that looks
        // unchanged (same tracker ids) but has gone silent must still be force-reconnected.
        // Without `requestReapply` clearing the dedupe gate, `dispatch()`'s
        // `hasApplied && ids == lastAppliedIds` short-circuit would make a watchdog-triggered
        // reconnect attempt silently do nothing.
        val (persist, gateway) = ports()
        val repository = newRepository(gateway, this, persist)

        repository.setLease(StreamingOwner.MAP, StreamIntent(trackerIds = setOf("a")))
        advanceUntilIdle()
        assertEquals(1, gateway.startedIds.size)

        repository.requestReapply(ReapplyReason.STALE_CONNECTION)
        advanceUntilIdle()

        assertEquals(2, gateway.startedIds.size)
        assertEquals(setOf("a"), gateway.startedIds.last())
    }

    @Test
    fun clearAllLeases_unconditionallyDispatchesStopEvenWithNoActiveLease() = runTest {
        val (persist, gateway) = ports()
        val repository = newRepository(gateway, this, persist)

        repository.clearAllLeases(ClearReason.LOGOUT)
        advanceUntilIdle()

        assertEquals(1, gateway.stopCount)
    }

    @Test
    fun clearAllLeases_clearsLeaseMapSoASubsequentIdenticalLeaseRedispatches() = runTest {
        val (persist, gateway) = ports()
        val repository = newRepository(gateway, this, persist)
        val lease = StreamIntent(trackerIds = setOf("a"))

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
        val (persist, gateway) = ports()
        val repository = newRepository(gateway, this, persist)

        repository.setLease(StreamingOwner.MAP, StreamIntent(trackerIds = setOf("a")))
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
        val (persist, gateway) = ports(persisted = setOf("restored") to "Restored")
        val repository = newRepository(gateway, this, persist)

        repository.seedFromPersistedState()

        assertEquals(setOf("restored"), repository.state.value.mergedTargets)
        assertEquals(ConnectionPhase.STARTING, repository.state.value.connection)

        // A real lease from any owner consumes/replaces the bootstrap seed rather than merging
        // with it -- see `setLease` clearing `bootstrapLease` whenever a non-null lease lands.
        repository.setLease(StreamingOwner.MAP, StreamIntent(trackerIds = setOf("real")))
        advanceUntilIdle()

        assertEquals(setOf("real"), repository.state.value.mergedTargets)
        assertEquals(listOf(setOf("real")), gateway.startedIds)
    }

    @Test
    fun seedFromPersistedState_neverEmitsAnIntermediateStateWhereTheBootstrapLeaseLooksAlreadyEnded() = runTest {
        // Regression test mirroring `setLease_neverEmitsAnIntermediateStateWhereANewLeaseLooksAlreadyEnded`:
        // `seedFromPersistedState` used to apply the bootstrap lease via `publishLeaseState()`
        // and then bump `connection` to STARTING as a *separate* `_state.update` call. A
        // collector using plain `collect` would observe the intermediate emission where the
        // bootstrap lease already made `wantsSubscription` true but `connection` was still the
        // freshly-constructed state's default IDLE -- indistinguishable from a session that ran
        // and ended. In production no collector exists yet at this point (see
        // `TrackerAppServices`' lazy construction), but this asserts the invariant holds
        // regardless of that wiring.
        val (persist, gateway) = ports(persisted = setOf("restored") to "Restored")
        val repository = newRepository(gateway, this, persist)
        val observedStates = mutableListOf<LiveStreamSubscriptionState>()
        val collectorJob = launch { repository.state.collect { observedStates.add(it) } }

        repository.seedFromPersistedState()
        advanceUntilIdle()

        collectorJob.cancel()
        val brokenEmissions = observedStates.filter { it.wantsSubscription && it.subscriptionEnded }
        assertTrue(
            "no emission should ever report wantsSubscription=true alongside subscriptionEnded=true, " +
                "but saw: $brokenEmissions",
            brokenEmissions.isEmpty(),
        )
    }

    @Test
    fun seedFromPersistedState_isNoOpWhenNothingWasPersisted() = runTest {
        val (persist, gateway) = ports(persisted = emptySet<String>() to null)
        val repository = newRepository(gateway, this, persist)

        repository.seedFromPersistedState()

        assertTrue(repository.state.value.mergedTargets.isEmpty())
        assertEquals(ConnectionPhase.IDLE, repository.state.value.connection)
    }

    @Test
    fun bootstrapLease_expiresAfterGraceWindowElapsesWithNoRealLeaseClaimed() = runTest {
        var nowMs = 0L
        val (persist, gateway) = ports(persisted = setOf("ghost") to "Ghost")
        val repository = newRepository(
            gateway,
            this,
            persist,
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
        val (persist, gateway) = ports(startResult = { _ -> LiveStreamApplyResult.Failed("boom") })
        val repository = newRepository(gateway, this, persist)

        repository.setLease(StreamingOwner.MAP, StreamIntent(trackerIds = setOf("a")))
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
        val (persist, gateway) = ports(stopResult = { LiveStreamStopResult.Failed("stop_boom") })
        val repository = newRepository(gateway, this, persist)

        repository.setLease(StreamingOwner.MAP, StreamIntent(trackerIds = setOf("a")))
        repository.setLease(StreamingOwner.MAP, null)
        advanceUntilIdle()

        assertEquals(ConnectionPhase.FAILED_TRANSIENT, repository.state.value.connection)
        assertEquals("stop_boom", repository.state.value.failureReason)
    }

    @Test
    fun reportConnectionUpdate_isOrthogonalToLeaseState() = runTest {
        val (persist, gateway) = ports()
        val repository = newRepository(gateway, this, persist)

        repository.setLease(StreamingOwner.MAP, StreamIntent(trackerIds = setOf("a")))
        advanceUntilIdle()

        repository.reportConnectionUpdate(ConnectionPhase.RECONNECTING, setOf("a"), "network_lost")

        // Wanting a subscription and the connection being unhealthy are independent axes: a
        // caller can still want the subscription while RECONNECTING.
        assertTrue(repository.state.value.wantsSubscription)
        assertFalse(repository.state.value.subscriptionHealthy)
        assertEquals("network_lost", repository.state.value.failureReason)
    }

    @Test
    fun reportConnectionUpdate_hasConnectedThisProcess_latchesTrueOnRunningAndStaysTrue() = runTest {
        val (persist, gateway) = ports()
        val repository = newRepository(gateway, this, persist)

        assertFalse(repository.state.value.hasConnectedThisProcess)

        repository.reportConnectionUpdate(ConnectionPhase.STARTING, emptySet(), null)
        assertFalse(repository.state.value.hasConnectedThisProcess)

        repository.reportConnectionUpdate(ConnectionPhase.RUNNING, setOf("a"), null)
        assertTrue(repository.state.value.hasConnectedThisProcess)

        // Sticky: a later disconnect/retry must not clear it back to false.
        repository.reportConnectionUpdate(ConnectionPhase.RECONNECTING, setOf("a"), "dropped")
        assertTrue(repository.state.value.hasConnectedThisProcess)
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
        val persist = FakeLiveStreamPersistPort()
        val gateway = object : LiveStreamHostPort {
            override fun apply(context: Context): LiveStreamApplyResult {
                val current = concurrentEntries.incrementAndGet()
                maxObservedConcurrency.updateAndGet { prev -> maxOf(prev, current) }
                if (isFirstCall.compareAndSet(true, false)) {
                    firstCallEntered.countDown()
                    releaseFirstCall.await(2, java.util.concurrent.TimeUnit.SECONDS)
                }
                concurrentEntries.decrementAndGet()
                return LiveStreamApplyResult.Started(persist.read().first)
            }

            override fun stop(context: Context): LiveStreamStopResult = LiveStreamStopResult.Stopped
            override fun reshow(context: Context) = Unit
            override fun cancelRetry() = Unit
        }
        val app: Context = ApplicationProvider.getApplicationContext()
        val realScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )
        val repository = LiveStreamSubscriptionRepository(
            appContext = app,
            persist = persist,
            host = gateway,
            dispatchDebounceMs = 0L,
            scope = realScope,
        )

        repository.setLease(StreamingOwner.MAP, StreamIntent(trackerIds = setOf("a")))
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

}
