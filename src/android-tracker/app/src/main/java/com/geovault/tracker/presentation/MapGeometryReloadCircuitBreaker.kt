package com.geovault.tracker.presentation

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounds and circuit-breaks the map's trail-reload geometry fetches, independently of the
 * shared Retrofit/OkHttp client's general-purpose 30s connect/read/write timeouts.
 *
 * Those client-wide timeouts exist to tolerate slow-but-legitimate requests across the whole
 * API surface. A trail *reload* can no longer block live-point rendering (see the
 * `trailReloadMutex` narrowing in [MapTrailReloadSubsystem]), but without a dedicated bound a
 * single fetch can still hang for up to ~90s (connect+read+write) before OkHttp itself gives
 * up — and every reload trigger in the meantime (a GPS fix, a roster change, a mode switch)
 * queues up another doomed attempt against an unreachable server. [NETWORK_TIMEOUT_MS] gives
 * up far sooner per attempt; the circuit breaker then skips new network attempts entirely for
 * [cooldownMs] once [failureThreshold] consecutive attempts have failed, so the
 * local-queue/degraded fallback already wired at every reload call site serves renders in the
 * meantime instead of every trigger paying the same timeout again.
 */
class MapGeometryReloadCircuitBreaker(
    private val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val nowMsProvider: () -> Long = System::currentTimeMillis,
) {
    private val consecutiveFailures = AtomicInteger(0)
    private val openedAtMs = AtomicLong(0L)

    /** Whether a network attempt should be made right now, or skipped straight to fallback. */
    fun shouldAttempt(): Boolean {
        val openedAt = openedAtMs.get()
        if (openedAt <= 0L) return true
        // Half-open: once the cooldown elapses, let a probe attempt through. A continued
        // failure re-opens the cooldown (via recordFailure); a success clears it entirely.
        return nowMsProvider() - openedAt >= cooldownMs
    }

    fun recordSuccess() {
        consecutiveFailures.set(0)
        openedAtMs.set(0L)
    }

    fun recordFailure() {
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            openedAtMs.set(nowMsProvider())
        }
    }

    fun resetForTests() {
        consecutiveFailures.set(0)
        openedAtMs.set(0L)
    }

    companion object {
        const val DEFAULT_FAILURE_THRESHOLD = 3
        val DEFAULT_COOLDOWN_MS: Long = TimeUnit.SECONDS.toMillis(45)

        /** Per-attempt timeout for a reload geometry fetch, well under the client's 30s legs. */
        val NETWORK_TIMEOUT_MS: Long = TimeUnit.SECONDS.toMillis(12)
    }
}
