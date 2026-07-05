package com.geovault.common.coroutines

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Launches a coroutine that collects [flow], restarting the collection (after [retryDelayMs])
 * if the collector lambda or the flow itself throws anything other than [CancellationException].
 *
 * A `viewModelScope.launch { flow.collect { ... } }` collector that is expected to run for the
 * lifetime of a screen normally relies on the flow itself never throwing -- but an unexpected
 * exception inside the collector body (a bug, a transient null, a downstream repository call
 * throwing) silently kills that one coroutine for good with no user-visible symptom beyond "this
 * part of the UI stopped updating." That is especially dangerous for the small number of
 * always-on collectors that drive a live-tracking map's core behavior (roster sync, streaming
 * reconcile, point ingestion): losing one permanently, with no telemetry, is a much worse
 * failure mode than a brief best-effort restart.
 *
 * [onError] is called with [tag] and the throwable before each restart so the caller can emit a
 * diagnostic breadcrumb; it is deliberately not built in here so this stays a general-purpose
 * primitive with no logging-framework dependency.
 */
fun <T> CoroutineScope.launchSupervisedCollector(
    tag: String,
    flow: Flow<T>,
    retryDelayMs: Long = 2_000L,
    onError: (tag: String, error: Throwable) -> Unit = { _, _ -> },
    collector: suspend (T) -> Unit,
): Job = launch {
    while (isActive) {
        try {
            flow.collect { value -> collector(value) }
            // The flow completed on its own (a finite flow, or the scope is winding down) --
            // restarting a completed flow forever would spin-loop, so exit instead.
            break
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            onError(tag, error)
            delay(retryDelayMs)
        }
    }
}
