package com.geovault.common.bootstrap

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Cold-start orchestrator shared by every GeoVault app.
 *
 * Runs every [GateTask] in parallel on `Dispatchers.IO` (blocking the calling thread until
 * all gates complete), flips [isReady] to `true`, then fires every [BackgroundTask] onto a
 * supervised long-lived scope. The splash screen is held up by `Activity.onCreate` until
 * [isReady] is `true`, so the first composition always sees preloaded state.
 *
 * Apps don't construct this directly — they use
 * [com.geovault.common.bootstrap.GeoVaultAppBootstrap] which wraps the orchestrator with
 * auth init, reset-hook registration, and pluggable subsystem extensions.
 *
 * Contract:
 *  - [boot] is called exactly once from `Application.onCreate`.
 *  - Failures in individual tasks are logged and swallowed so a single misbehaving
 *    warm-up never hangs the splash or crashes the process — the app starts in a
 *    degraded but recoverable state.
 */
class GeoVaultColdStart(
    private val gates: List<GateTask>,
    private val background: List<BackgroundTask>,
    private val backgroundScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val readyState = MutableStateFlow(false)

    /**
     * `true` once every [GateTask] has completed (success or failure). Observed by the
     * splash-screen helper so the OS splash dismisses as soon as preload is done.
     */
    val isReady: StateFlow<Boolean> = readyState.asStateFlow()

    /**
     * Run every gate in parallel, block until all complete, then launch every
     * background task on [backgroundScope]. Idempotent — subsequent calls no-op.
     */
    fun boot(context: Context) {
        if (readyState.value) return
        runBlocking {
            gates.map { task ->
                async(Dispatchers.IO) { runTaskSafely(task, context) }
            }.awaitAll()
        }
        readyState.value = true
        background.forEach { task ->
            backgroundScope.launch { runTaskSafely(task, context) }
        }
    }

    private suspend fun runTaskSafely(task: ColdStartTask, context: Context) {
        val started = System.currentTimeMillis()
        runCatching { task.execute(context) }
            .onSuccess {
                val elapsed = System.currentTimeMillis() - started
                Log.d(TAG, "task '${task.id}' ok (${elapsed}ms)")
            }
            .onFailure { err ->
                val elapsed = System.currentTimeMillis() - started
                Log.w(TAG, "task '${task.id}' failed after ${elapsed}ms", err)
            }
    }

    private companion object {
        const val TAG = "GeoVaultColdStart"
    }
}
