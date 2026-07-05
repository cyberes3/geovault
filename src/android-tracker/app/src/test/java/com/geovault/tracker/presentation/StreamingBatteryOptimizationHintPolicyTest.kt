package com.geovault.tracker.presentation

import com.geovault.tracker.streaming.StreamingConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingBatteryOptimizationHintPolicyTest {

    private val thresholdMs = StreamingConfig.batteryOptimizationHintUnhealthyThresholdMs

    @Test
    fun showsHint_whenUnhealthyForAtLeastTheThresholdWithNetworkAndNoExemption() {
        val nowMs = 1_000_000L
        val shown = StreamingBatteryOptimizationHintPolicy.shouldShowHint(
            wantsSubscription = true,
            connectionHealthy = false,
            unhealthySinceMs = nowMs - thresholdMs,
            nowMs = nowMs,
            hasUsableNetwork = true,
            hasBatteryOptimizationExemption = false,
        )

        assertTrue(shown)
    }

    @Test
    fun doesNotShowHint_beforeThresholdElapses() {
        val nowMs = 1_000_000L
        val shown = StreamingBatteryOptimizationHintPolicy.shouldShowHint(
            wantsSubscription = true,
            connectionHealthy = false,
            unhealthySinceMs = nowMs - (thresholdMs - 1L),
            nowMs = nowMs,
            hasUsableNetwork = true,
            hasBatteryOptimizationExemption = false,
        )

        assertFalse(shown)
    }

    @Test
    fun doesNotShowHint_whenNoSubscriptionIsWanted() {
        // A user who isn't streaming at all shouldn't be nagged about battery optimization for a
        // connection they never asked for.
        val nowMs = 1_000_000L
        val shown = StreamingBatteryOptimizationHintPolicy.shouldShowHint(
            wantsSubscription = false,
            connectionHealthy = false,
            unhealthySinceMs = nowMs - thresholdMs,
            nowMs = nowMs,
            hasUsableNetwork = true,
            hasBatteryOptimizationExemption = false,
        )

        assertFalse(shown)
    }

    @Test
    fun doesNotShowHint_whenConnectionIsHealthy() {
        val nowMs = 1_000_000L
        val shown = StreamingBatteryOptimizationHintPolicy.shouldShowHint(
            wantsSubscription = true,
            connectionHealthy = true,
            unhealthySinceMs = nowMs - thresholdMs,
            nowMs = nowMs,
            hasUsableNetwork = true,
            hasBatteryOptimizationExemption = false,
        )

        assertFalse(shown)
    }

    @Test
    fun doesNotShowHint_whenExemptionIsAlreadyGranted() {
        // The whole point of the hint is to prompt the user toward granting the exemption; once
        // granted, nagging further would be pointless even if the connection hasn't recovered yet.
        val nowMs = 1_000_000L
        val shown = StreamingBatteryOptimizationHintPolicy.shouldShowHint(
            wantsSubscription = true,
            connectionHealthy = false,
            unhealthySinceMs = nowMs - thresholdMs,
            nowMs = nowMs,
            hasUsableNetwork = true,
            hasBatteryOptimizationExemption = true,
        )

        assertFalse(shown)
    }

    @Test
    fun doesNotShowHint_whenNoUsableNetworkIsPresent() {
        // Without a network at all, the disconnect is much more likely explained by "no
        // internet" than by the OS specifically killing this app in the background -- showing
        // the battery hint here would send the user down the wrong troubleshooting path.
        val nowMs = 1_000_000L
        val shown = StreamingBatteryOptimizationHintPolicy.shouldShowHint(
            wantsSubscription = true,
            connectionHealthy = false,
            unhealthySinceMs = nowMs - thresholdMs,
            nowMs = nowMs,
            hasUsableNetwork = false,
            hasBatteryOptimizationExemption = false,
        )

        assertFalse(shown)
    }

    @Test
    fun doesNotShowHint_whenUnhealthySinceIsNull() {
        // `null` means "not currently tracked as unhealthy" (e.g. the very first heartbeat tick
        // after becoming unhealthy, before the timestamp is recorded) -- must never be treated as
        // "unhealthy forever."
        val shown = StreamingBatteryOptimizationHintPolicy.shouldShowHint(
            wantsSubscription = true,
            connectionHealthy = false,
            unhealthySinceMs = null,
            nowMs = 1_000_000L,
            hasUsableNetwork = true,
            hasBatteryOptimizationExemption = false,
        )

        assertFalse(shown)
    }
}
