package com.geovault.tracker.runtime

import com.geovault.tracker.settings.TrackerSettingsLoadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRecoveryInvariantsTest {

    @Test
    fun watchdogTick_restartDisabled_neverDesired() {
        val decision = RecoveryPolicy.decide(
            RecoveryInput(
                source = RecoverySource.WatchdogTick,
                shouldBeRunning = true,
                restartTrackingIfKilled = false,
                startOnBoot = false,
                serviceRunning = false,
                settingsLoadState = TrackerSettingsLoadState.Ready,
                userUnlocked = true,
                hasRequiredPermissions = true,
                gpsProviderEnabled = true,
                selectedTrackerId = "11111111-1111-1111-1111-111111111111",
                lastHeartbeatAtMs = 0L,
                nowMs = 60_000L,
            ),
        )
        assertTrue(decision is RecoveryDecision.Abandon)
        assertEquals("watchdog_disabled", (decision as RecoveryDecision.Abandon).reason)
    }

    @Test
    fun watchdogTick_shouldNotBeRunning_skipsRecovery() {
        val decision = RecoveryPolicy.decide(
            RecoveryInput(
                source = RecoverySource.WatchdogTick,
                shouldBeRunning = false,
                restartTrackingIfKilled = true,
                startOnBoot = false,
                serviceRunning = false,
                settingsLoadState = TrackerSettingsLoadState.Ready,
                userUnlocked = true,
                hasRequiredPermissions = true,
                gpsProviderEnabled = true,
                selectedTrackerId = "11111111-1111-1111-1111-111111111111",
                lastHeartbeatAtMs = 0L,
                nowMs = 60_000L,
            ),
        )
        assertTrue(decision is RecoveryDecision.Abandon)
        assertEquals("watchdog_disabled", (decision as RecoveryDecision.Abandon).reason)
    }

    @Test
    fun watchdogTick_staleHeartbeat_attemptsStart() {
        val decision = RecoveryPolicy.decide(
            RecoveryInput(
                source = RecoverySource.WatchdogTick,
                shouldBeRunning = true,
                restartTrackingIfKilled = true,
                startOnBoot = false,
                serviceRunning = false,
                settingsLoadState = TrackerSettingsLoadState.Ready,
                userUnlocked = true,
                hasRequiredPermissions = true,
                gpsProviderEnabled = true,
                selectedTrackerId = "11111111-1111-1111-1111-111111111111",
                lastHeartbeatAtMs = 0L,
                nowMs = 60_000L,
            ),
        )
        assertTrue(decision is RecoveryDecision.AttemptStart)
        assertEquals("heartbeat_stale", (decision as RecoveryDecision.AttemptStart).reason)
    }
}
