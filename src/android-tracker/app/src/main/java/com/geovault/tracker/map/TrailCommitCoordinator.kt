package com.geovault.tracker.map

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * MUTEX-SCOPE: guards the single commit point shared by every writer that mutates trail/session
 * state derived from a fetch or a live point -- [MapTrailReloadSubsystem]'s reload commits,
 * [MapTrailDisplaySubsystem]'s reproject/session commits, and the live track-point consumer in
 * [MapStreamingSubsystem]. Without this, two independent fetches (or a fetch racing a live point)
 * could interleave their reads and writes of [TrackerMapStateHub.uiStateMutable] and silently drop
 * one side's update. Named for the invariant it protects (a single, serialized commit) rather
 * than the primitive underneath, so call sites read as intent instead of lock plumbing.
 */
internal class TrailCommitCoordinator {
    private val mutex = Mutex()

    val isLocked: Boolean get() = mutex.isLocked

    suspend fun <T> withCommitLock(block: suspend () -> T): T = mutex.withLock { block() }
}
