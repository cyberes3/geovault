package com.geovault.tracker.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapGeometryReloadCircuitBreakerTest {

    // Base wall-clock time for every test. `openedAtMs` uses 0 as its "not open" sentinel
    // (matching this codebase's convention elsewhere, e.g. `StreamingSessionGuard`), so tests
    // must start the clock at a realistic nonzero value or a genuine open-at-time-0 case would
    // be misread as "never opened".
    private val baseNowMs = 1_700_000_000_000L

    private fun breaker(
        failureThreshold: Int = 3,
        cooldownMs: Long = 45_000L,
        nowMs: () -> Long,
    ) = MapGeometryReloadCircuitBreaker(
        failureThreshold = failureThreshold,
        cooldownMs = cooldownMs,
        nowMsProvider = nowMs,
    )

    @Test
    fun attemptsAllowedByDefault() {
        assertTrue(breaker(nowMs = { baseNowMs }).shouldAttempt())
    }

    @Test
    fun staysClosedBelowFailureThreshold() {
        val sut = breaker(failureThreshold = 3, nowMs = { baseNowMs })
        sut.recordFailure()
        sut.recordFailure()
        assertTrue(sut.shouldAttempt())
    }

    @Test
    fun opensAfterConsecutiveFailuresReachThreshold() {
        val sut = breaker(failureThreshold = 3, nowMs = { baseNowMs })
        sut.recordFailure()
        sut.recordFailure()
        sut.recordFailure()
        assertFalse(sut.shouldAttempt())
    }

    @Test
    fun successResetsFailureCount() {
        var now = baseNowMs
        val sut = breaker(failureThreshold = 3, nowMs = { now })
        sut.recordFailure()
        sut.recordFailure()
        sut.recordSuccess()
        sut.recordFailure()
        sut.recordFailure()
        // Only 2 consecutive failures since the reset — still below the threshold of 3.
        assertTrue(sut.shouldAttempt())
    }

    @Test
    fun remainsOpenBeforeCooldownElapses() {
        var now = baseNowMs
        val sut = breaker(failureThreshold = 3, cooldownMs = 45_000L, nowMs = { now })
        repeat(3) { sut.recordFailure() }
        now = baseNowMs + 44_999L
        assertFalse(sut.shouldAttempt())
    }

    @Test
    fun halfOpensAfterCooldownElapses() {
        var now = baseNowMs
        val sut = breaker(failureThreshold = 3, cooldownMs = 45_000L, nowMs = { now })
        repeat(3) { sut.recordFailure() }
        now = baseNowMs + 45_000L
        assertTrue(sut.shouldAttempt())
    }

    @Test
    fun failedProbeReopensCooldownFromProbeTime() {
        var now = baseNowMs
        val sut = breaker(failureThreshold = 3, cooldownMs = 45_000L, nowMs = { now })
        repeat(3) { sut.recordFailure() }
        now = baseNowMs + 45_000L
        assertTrue(sut.shouldAttempt())
        sut.recordFailure()
        now = baseNowMs + 45_001L
        assertFalse(sut.shouldAttempt())
        now = baseNowMs + 90_000L
        assertTrue(sut.shouldAttempt())
    }

    @Test
    fun successfulProbeClosesCircuit() {
        var now = baseNowMs
        val sut = breaker(failureThreshold = 3, cooldownMs = 45_000L, nowMs = { now })
        repeat(3) { sut.recordFailure() }
        now = baseNowMs + 45_000L
        assertTrue(sut.shouldAttempt())
        sut.recordSuccess()
        now = baseNowMs + 45_001L
        assertTrue(sut.shouldAttempt())
    }

    @Test
    fun resetForTestsClearsState() {
        val now = baseNowMs
        val sut = breaker(failureThreshold = 3, cooldownMs = 45_000L, nowMs = { now })
        repeat(3) { sut.recordFailure() }
        assertFalse(sut.shouldAttempt())
        sut.resetForTests()
        assertTrue(sut.shouldAttempt())
    }
}
