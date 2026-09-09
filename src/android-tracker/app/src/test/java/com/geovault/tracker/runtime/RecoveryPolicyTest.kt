package com.geovault.tracker.runtime

import com.geovault.tracker.settings.TrackerSettingsLoadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryPolicyTest {

    @Test
    fun watchdog_disabledWhenNotDesired() {
        val decision = RecoveryPolicy.decide(
            watchdog(shouldBeRunning = true, restartTrackingIfKilled = false),
        )
        assertTrue(decision is RecoveryDecision.Abandon)
        assertEquals("watchdog_disabled", (decision as RecoveryDecision.Abandon).reason)
    }

    @Test
    fun watchdog_disabledWhenShouldNotBeRunning() {
        val decision = RecoveryPolicy.decide(
            watchdog(shouldBeRunning = false, restartTrackingIfKilled = true),
        )
        assertTrue(decision is RecoveryDecision.Abandon)
        assertEquals("watchdog_disabled", (decision as RecoveryDecision.Abandon).reason)
    }

    @Test
    fun watchdog_attemptsStartWhenHeartbeatStale() {
        val decision = RecoveryPolicy.decide(
            watchdog(shouldBeRunning = true, lastHeartbeatAtMs = 0L, nowMs = 60_000L, serviceRunning = false),
        )
        assertTrue(decision is RecoveryDecision.AttemptStart)
        assertEquals("heartbeat_stale", (decision as RecoveryDecision.AttemptStart).reason)
    }

    @Test
    fun watchdog_skipsWhenHeartbeatFresh() {
        val decision = RecoveryPolicy.decide(
            watchdog(shouldBeRunning = true, lastHeartbeatAtMs = 50_000L, nowMs = 60_000L),
        )
        assertTrue(decision is RecoveryDecision.Abandon)
        assertEquals("heartbeat_fresh", (decision as RecoveryDecision.Abandon).reason)
    }

    @Test
    fun watchdog_defersWhileSettingsLoading() {
        val decision = RecoveryPolicy.decide(
            watchdog(shouldBeRunning = true).copy(settingsLoadState = TrackerSettingsLoadState.Loading),
        )
        assertTrue(decision is RecoveryDecision.Defer)
        assertEquals(RecoveryPolicy.LOADING_RETRY_MS, (decision as RecoveryDecision.Defer).delayMs)
    }

    @Test
    fun watchdog_defersOnSettingsError() {
        val decision = RecoveryPolicy.decide(
            watchdog(shouldBeRunning = true).copy(settingsLoadState = TrackerSettingsLoadState.Error),
        )
        assertTrue(decision is RecoveryDecision.Defer)
        assertEquals(RecoveryPolicy.SETTINGS_ERROR_RETRY_MS, (decision as RecoveryDecision.Defer).delayMs)
    }

    @Test
    fun coldStart_readyAndShouldBeRunning_arms() {
        val decision = RecoveryPolicy.decide(
            watchdog(shouldBeRunning = true, serviceRunning = false).copy(source = RecoverySource.ColdStart),
        )
        assertTrue(decision is RecoveryDecision.Arm)
    }

    @Test
    fun coldStart_readyAndNotDesired_abandons() {
        val decision = RecoveryPolicy.decide(
            watchdog(shouldBeRunning = false, serviceRunning = false).copy(source = RecoverySource.ColdStart),
        )
        assertTrue(decision is RecoveryDecision.Abandon)
    }

    @Test
    fun boot_attemptsStartWhenStartOnBoot() {
        val decision = RecoveryPolicy.decide(
            watchdog(shouldBeRunning = false, serviceRunning = false).copy(
                source = RecoverySource.Boot,
                startOnBoot = true,
            ),
        )
        assertTrue(decision is RecoveryDecision.AttemptStart)
    }

    @Test
    fun watchdog_skipsWhenServiceAliveAndStale() {
        val decision = RecoveryPolicy.decide(
            watchdog(
                shouldBeRunning = true,
                lastHeartbeatAtMs = 0L,
                nowMs = 60_000L,
                serviceRunning = true,
            ),
        )
        assertTrue(decision is RecoveryDecision.Abandon)
        assertEquals("heartbeat_stale_service_alive", (decision as RecoveryDecision.Abandon).reason)
    }

    private fun watchdog(
        shouldBeRunning: Boolean,
        restartTrackingIfKilled: Boolean = true,
        lastHeartbeatAtMs: Long = 0L,
        nowMs: Long = 60_000L,
        serviceRunning: Boolean = false,
    ): RecoveryInput {
        return RecoveryInput(
            source = RecoverySource.WatchdogTick,
            shouldBeRunning = shouldBeRunning,
            restartTrackingIfKilled = restartTrackingIfKilled,
            startOnBoot = false,
            serviceRunning = serviceRunning,
            settingsLoadState = TrackerSettingsLoadState.Ready,
            userUnlocked = true,
            hasRequiredPermissions = true,
            gpsProviderEnabled = true,
            selectedTrackerId = "11111111-1111-1111-1111-111111111111",
            lastHeartbeatAtMs = lastHeartbeatAtMs,
            nowMs = nowMs,
        )
    }
}
