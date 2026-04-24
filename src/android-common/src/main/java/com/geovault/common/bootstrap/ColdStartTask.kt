package com.geovault.common.bootstrap

import android.content.Context

/**
 * A single piece of cold-start work.
 *
 * Tasks are composed into a [GeoVaultColdStart] orchestrator. Each task has a single
 * responsibility — read a piece of prefs, open a database, precompute a UI snapshot —
 * and writes its result (if any) into a typed cache that the rest of the app reads once
 * during its initial composition.
 *
 * There are exactly two kinds of tasks:
 *  - [GateTask] — must complete before the first UI frame paints. Gates run in parallel
 *    on [kotlinx.coroutines.Dispatchers.IO] and are awaited by the orchestrator's `boot()`
 *    call inside `Application.onCreate`.
 *  - [BackgroundTask] — latency-tolerant warm-up that may run after the UI is already
 *    live. Fires-and-forgets on the orchestrator's supervised scope.
 *
 * Tasks MUST be:
 *  - idempotent (safe to call twice),
 *  - self-contained (no cross-task dependencies beyond shared caches and well-documented
 *    ordering guarantees from synchronized singletons),
 *  - fast — any expensive step belongs in a [BackgroundTask], never a gate.
 */
sealed interface ColdStartTask {
    /** Stable id for logs/metrics. Must be unique across all tasks in an orchestrator. */
    val id: String

    /** Perform the work. Called exactly once per `GeoVaultColdStart.boot()` invocation. */
    suspend fun execute(context: Context)
}

/**
 * A task that must complete before the splash is dismissed and the first frame paints.
 * Gate tasks should be CPU/IO light; aim for <50 ms on a warm device.
 */
abstract class GateTask(override val id: String) : ColdStartTask

/**
 * A task that may run after the UI is live. Use for map pre-warming, asset copies,
 * version checks — anything the user doesn't directly perceive during launch.
 */
abstract class BackgroundTask(override val id: String) : ColdStartTask
