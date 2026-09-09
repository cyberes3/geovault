package com.geovault.tracker.streaming

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Single application-scoped owner of "what should be streaming" and "what is actually
 * streaming" for the live-track websocket.
 *
 * Design:
 * - [setLease] lets each [StreamingOwner] (Map, Params) declare its own independent intent; the
 *   merged target set is always the union (minus every owner's locally-recorded id) — see
 *   [LiveStreamSubscriptionState.mergedTargets]. Because the union is order-independent and
 *   idempotent, an owner never needs a special "is someone else already streaming this?" case:
 *   it just always holds its own lease for whatever it's viewing.
 * - A short debounce ([StreamingConfig.dispatchDebounceMs]) absorbs rapid lease churn (e.g. a
 *   user quickly scrolling through trackers) into a single dispatch instead of thrashing
 *   connect/disconnect.
 * - [seedFromPersistedState] installs a transient "bootstrap" lease from the service's
 *   SharedPreferences-persisted targets *before* any real owner has expressed a lease. This is
 *   folded into the merge like any other lease, so the very first reconcile tick after a cold
 *   start (before the map ViewModel's roster has loaded) computes the *persisted* target set
 *   instead of an empty one — eliminating the race where a premature empty lease from an
 *   unloaded UI would issue a spurious stop to a session the service already restored via
 *   `START_STICKY`. The bootstrap lease is consumed (removed from the merge permanently) as soon
 *   as any owner expresses a real lease, or after [StreamingConfig.bootstrapGraceMs] elapses,
 *   whichever comes first — bounding how long a session nobody has claimed can be kept alive.
 * - [requestReapply] is the single, unconditional entry point for "force a restart even with
 *   identical target ids" — used by the liveness watchdog, by resume-from-background, and by
 *   dispatch-failure recovery.
 * - [reportConnectionHealth] is the only writer of the connection-health axis. Lease state and
 *   connection-health are orthogonal: a caller can want a subscription while RECONNECTING.
 */
internal class LiveStreamSubscriptionRepository(
    private val appContext: Context,
    persist: LiveStreamPersistPort? = null,
    host: LiveStreamHostPort? = null,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val dispatchDebounceMs: Long = StreamingConfig.dispatchDebounceMs,
    private val bootstrapGraceMs: Long = StreamingConfig.bootstrapGraceMs,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val persist: LiveStreamPersistPort = persist ?: SharedPrefsLiveStreamPersistPort(appContext)
    private val host: LiveStreamHostPort = host ?: DefaultLiveStreamHostPort(appContext, this.persist)
    private val lock = Any()
    private val _state = MutableStateFlow(LiveStreamSubscriptionState())
    val state: StateFlow<LiveStreamSubscriptionState> = _state.asStateFlow()

    private val leases = mutableMapOf<StreamingOwner, StreamIntent>()
    private var bootstrapLease: StreamIntent? = null
    private var bootstrapDeadlineElapsedMs: Long = 0L
    private var hasApplied = false
    private var lastAppliedIds: Set<String> = emptySet()
    private var lastAppliedName: String? = null
    private var dispatchJob: Job? = null
    private var dispatchGeneration = 0L

    /**
     * Seeds the bootstrap lease from whatever the service last persisted. Must be called once,
     * before the first real [setLease] call, from [LiveStreamBootstrapper]. A no-op if nothing
     * was persisted (fresh install / clean logout).
     */
    fun seedFromPersistedState() {
        val (ids, name) = runCatching { persist.read() }
            .getOrDefault(emptySet<String>() to null)
        if (ids.isEmpty()) return
        val (leasesSnapshot, bootstrap) = synchronized(lock) {
            bootstrapLease = StreamIntent(trackerIds = ids, displayName = name)
            bootstrapDeadlineElapsedMs = elapsedRealtimeMs() + bootstrapGraceMs
            leases.toMap() to bootstrapLease
        }
        // BOOTSTRAP-SEED RACE: same hazard `setLease` documents in detail -- a separate
        // `publishLeaseState()` followed by a STARTING fix-up would let a plain-`collect`
        // consumer see the bootstrap lease already making `wantsSubscription` true while
        // `connection` is still the freshly-constructed state's default `IDLE`, which is
        // indistinguishable from a session that ran and stopped. Folding the lease-derived
        // fields and the STARTING seed into one `update {}` closes that window the same way.
        // (Today this repository is only ever handed to a caller *after* this method returns
        // -- see `TrackerAppServices`' lazy construction, which runs this before anything can
        // hold a reference to `state` -- so no collector actually exists yet to observe the
        // gap; fixed anyway so the invariant doesn't depend on that wiring staying true.)
        _state.update {
            it.copy(
                leases = leasesSnapshot,
                bootstrapLease = bootstrap,
                activeTargets = ids,
                connection = ConnectionPhase.STARTING,
            )
        }
    }

    /** Replaces [owner]'s lease. `null` drops it. A no-op (no dispatch) if the value is unchanged. */
    fun setLease(owner: StreamingOwner, lease: StreamIntent?) {
        val changed = synchronized(lock) {
            val previous = leases[owner]
            if (previous == lease) return@synchronized false
            if (lease == null) leases.remove(owner) else leases[owner] = lease
            if (lease != null) bootstrapLease = null
            true
        }
        if (!changed) return
        // PENDING-START RACE: this must land as a *single* `_state` emission, not
        // `publishLeaseState()` followed by a separate STARTING fix-up. `StateFlow` collectors
        // that use plain `collect` (as `MapSessionEngine`'s stream-state collector
        // deliberately does) observe every distinct emission, so a two-step update would let
        // them see the intermediate value in between: leases already reflect the new lease
        // (`wantsSubscription=true`) but `connection` is still the stale `IDLE`/
        // `FAILED_PERMANENT` left over from the previous session — indistinguishable from
        // `subscriptionEnded` for a session that actually ran and stopped. That false "ended"
        // reading tore down the lease this call just set, before the service ever got a chance
        // to try connecting, and did so on *every* call to `setLease` since `dispatch()` (and
        // the real `servicePort.startStreaming` that would move `connection` off IDLE) doesn't
        // run until after `dispatchDebounceMs` elapses. Computing both the lease-derived fields
        // and the STARTING bump inside one `update {}` block closes that window: whatever a
        // collector sees is either the fully-old or the fully-new state, never a hybrid of the
        // two. Mirrors `seedFromPersistedState`'s identical STARTING seed for the same reason.
        val (leasesSnapshot, bootstrap) = synchronized(lock) { leases.toMap() to bootstrapLease }
        _state.update { current ->
            val withLeases = current.copy(leases = leasesSnapshot, bootstrapLease = bootstrap)
            if (withLeases.wantsSubscription && withLeases.subscriptionEnded) {
                withLeases.copy(connection = ConnectionPhase.STARTING)
            } else {
                withLeases
            }
        }
        scheduleDispatch(immediate = false)
    }

    /** Unconditionally clears the dedupe gate and re-dispatches the current merged plan, even if unchanged. */
    fun requestReapply(reason: ReapplyReason) {
        synchronized(lock) { hasApplied = false }
        scheduleDispatch(immediate = true, reason = reason.name)
    }

    /** Drops every lease and unconditionally dispatches a stop. Used by logout / account reset. */
    fun clearAllLeases(reason: ClearReason) {
        synchronized(lock) {
            leases.clear()
            bootstrapLease = null
            hasApplied = false
        }
        publishLeaseState()
        scheduleDispatch(immediate = true, reason = reason.name)
    }

    /**
     * Drops every in-memory lease without dispatching anything. Used only by the service's own
     * stop path, which is already tearing itself down via [servicePort] directly — dispatching
     * again here would just be a redundant, racy stop command against a service mid-shutdown.
     */
    internal fun clearLeasesWithoutDispatch() {
        synchronized(lock) {
            // Bumping the generation here (not just cancelling the job) is what actually matters:
            // see the [dispatch] doc for why cancellation alone can't stop an in-flight tick.
            dispatchJob?.cancel()
            ++dispatchGeneration
            leases.clear()
            bootstrapLease = null
            hasApplied = false
        }
        publishLeaseState()
    }

    fun reportConnectionHealth(health: ConnectionHealth) {
        reportConnectionUpdate(
            connection = health.phase,
            activeTargets = health.activeTargets,
            failureReason = health.failureReason,
        )
    }

    fun pruneInvalidTargets(validIds: Set<String>) {
        val changed = synchronized(lock) {
            var mutated = false
            val owners = leases.keys.toList()
            for (owner in owners) {
                val lease = leases[owner] ?: continue
                val pruned = lease.trackerIds.intersect(validIds)
                if (pruned == lease.trackerIds) continue
                mutated = true
                if (pruned.isEmpty()) {
                    leases.remove(owner)
                } else {
                    leases[owner] = lease.copy(trackerIds = pruned)
                }
            }
            bootstrapLease?.let { seed ->
                val pruned = seed.trackerIds.intersect(validIds)
                if (pruned != seed.trackerIds) {
                    mutated = true
                    bootstrapLease = if (pruned.isEmpty()) null else seed.copy(trackerIds = pruned)
                }
            }
            mutated
        }
        if (!changed) return
        publishLeaseState()
        scheduleDispatch(immediate = true, reason = "prune")
    }

    /** Called exclusively by [com.geovault.tracker.LiveTrackStreamingService] to report the connection-health axis. */
    fun reportConnectionUpdate(connection: ConnectionPhase, activeTargets: Set<String>, failureReason: String?) {
        _state.update {
            it.copy(
                connection = connection,
                activeTargets = activeTargets,
                failureReason = failureReason,
                hasConnectedThisProcess = it.hasConnectedThisProcess || connection == ConnectionPhase.RUNNING,
            )
        }
    }

    private fun publishLeaseState() {
        val (leasesSnapshot, bootstrap) = synchronized(lock) { leases.toMap() to bootstrapLease }
        _state.update { it.copy(leases = leasesSnapshot, bootstrapLease = bootstrap) }
    }

    /**
     * Mutating [dispatchJob]/[dispatchGeneration] under [lock] (rather than as plain field
     * writes) matters because [setLease]/[requestReapply]/[clearAllLeases] are called from at
     * least four different scopes/threads ([LiveTrackStreamingService]'s own lifecycle, the map
     * ViewModel's reconciler, [com.geovault.tracker.presentation.TrackerParamsStreamingController],
     * and `MainActivity`) — two of them racing here without a lock could stomp each other's
     * generation bump or job assignment. See [dispatch] for why the generation check itself also
     * has to live inside the same lock as the dispatch it's guarding.
     */
    private fun scheduleDispatch(immediate: Boolean, reason: String? = null) {
        synchronized(lock) {
            dispatchJob?.cancel()
            val generation = ++dispatchGeneration
            dispatchJob = scope.launch {
                if (!immediate && dispatchDebounceMs > 0L) delay(dispatchDebounceMs)
                dispatch(reason, generation)
            }
        }
    }

    /**
     * Runs entirely inside [lock] so two dispatch ticks racing on [scope]'s multi-threaded IO
     * dispatcher cannot execute concurrently. [Job.cancel] in [scheduleDispatch] is only
     * cooperative -- [dispatchStart]/[dispatchStop] are non-suspending -- so a cancelled tick that
     * already entered here would otherwise finish in parallel with a newer tick. Serializing on
     * [lock] makes the [generation] check-and-act atomic: the losing tick re-reads state under the
     * same lock and no-ops if [dispatchGeneration] moved or [hasApplied]/[lastAppliedIds] already
     * match.
     */
    private fun dispatch(reason: String?, generation: Long) {
        synchronized(lock) {
            if (generation != dispatchGeneration) return
            expireBootstrapIfDueLocked()
            publishLeaseState()
            val snapshot = _state.value
            val ids = snapshot.mergedTargets
            val name = snapshot.displayName
            val shouldSkip = hasApplied && ids == lastAppliedIds && name == lastAppliedName
            if (shouldSkip) return
            StreamingDiagnostics.logDispatch(reason = reason, trackerIds = ids, trackerName = name)
            if (ids.isEmpty()) {
                dispatchStop(ids, name)
            } else {
                dispatchStart(ids, name)
            }
        }
    }

    private fun dispatchStop(ids: Set<String>, name: String?) {
        when (val result = host.stop(appContext)) {
            LiveStreamStopResult.Stopped -> {
                synchronized(lock) {
                    hasApplied = true
                    lastAppliedIds = ids
                    lastAppliedName = name
                }
                _state.update { it.copy(lastDispatchedCommand = DispatchedCommand(ids, name)) }
            }
            is LiveStreamStopResult.Failed -> {
                synchronized(lock) { hasApplied = false }
                _state.update { it.copy(connection = ConnectionPhase.FAILED_TRANSIENT, failureReason = result.reason) }
            }
        }
    }

    private fun dispatchStart(ids: Set<String>, name: String?) {
        persist.commit(ids, name)
        when (val result = host.apply(appContext)) {
            is LiveStreamApplyResult.Started -> {
                synchronized(lock) {
                    hasApplied = true
                    lastAppliedIds = ids
                    lastAppliedName = name
                }
                _state.update { it.copy(lastDispatchedCommand = DispatchedCommand(ids, name)) }
            }
            is LiveStreamApplyResult.Failed -> {
                val stopResult = host.stop(appContext)
                synchronized(lock) { hasApplied = false }
                val stoppedCleanly = stopResult == LiveStreamStopResult.Stopped
                val failureReason = when (stopResult) {
                    LiveStreamStopResult.Stopped -> result.reason
                    is LiveStreamStopResult.Failed -> "${result.reason}; stop_failed:${stopResult.reason}"
                }
                _state.update { current ->
                    current.copy(
                        connection = if (stoppedCleanly) ConnectionPhase.IDLE else ConnectionPhase.FAILED_TRANSIENT,
                        activeTargets = if (stoppedCleanly) emptySet() else current.activeTargets,
                        failureReason = failureReason,
                    )
                }
            }
        }
    }

    private fun expireBootstrapIfDueLocked() {
        val deadline = bootstrapDeadlineElapsedMs
        if (bootstrapLease != null && deadline in 1..elapsedRealtimeMs()) {
            bootstrapLease = null
        }
    }
}

