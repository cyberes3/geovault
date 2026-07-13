package com.geovault.common.coroutines

import kotlinx.coroutines.CancellationException

/**
 * Rethrows this [Throwable] if it is a [CancellationException], otherwise returns it unchanged.
 *
 * `runCatching { ... }` and a bare `catch (t: Throwable)` around a suspend call both catch
 * coroutine cancellation along with real failures. Left unchecked, cancelling that coroutine --
 * e.g. by navigating away from a screen mid-request, or a newer request superseding this one --
 * gets treated as a genuine failure and can surface a spurious error toast/dialog. Call this at
 * the top of the failure handler (`result.onFailure { it.rethrowIfCancellation(); ... }` or
 * `catch (t: Throwable) { t.rethrowIfCancellation(); ... }`) to restore normal cancellation
 * propagation before deciding how to report the error.
 */
fun Throwable.rethrowIfCancellation(): Throwable {
    if (this is CancellationException) throw this
    return this
}
