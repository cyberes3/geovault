package com.geovault.tracker.map

import com.geovault.tracker.streaming.StreamingConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapSessionBatteryOptimizationHintTest {

    private val thresholdMs = StreamingConfig.batteryOptimizationHintUnhealthyThresholdMs

    @Test
    fun showsHint_whenUnhealthyForAtLeastTheThresholdWithNetworkAndNoExemption() {
        val nowMs = 1_000_000L
        val shown = MapSessionEngine.shouldShowBatteryOptimizationHint(
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
        val shown = MapSessionEngine.shouldShowBatteryOptimizationHint(
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
        val nowMs = 1_000_000L
        val shown = MapSessionEngine.shouldShowBatteryOptimizationHint(
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
        val shown = MapSessionEngine.shouldShowBatteryOptimizationHint(
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
        val nowMs = 1_000_000L
        val shown = MapSessionEngine.shouldShowBatteryOptimizationHint(
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
        val nowMs = 1_000_000L
        val shown = MapSessionEngine.shouldShowBatteryOptimizationHint(
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
        val shown = MapSessionEngine.shouldShowBatteryOptimizationHint(
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
