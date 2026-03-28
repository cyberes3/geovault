package com.geovault.tracker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingRecoveryCoordinatorTest {

    @Test
    fun evaluateRecovery_returnsStart_whenHeartbeatIsStaleAndRestartEnabled() {
        val now = 10_000L
        val decision = TrackingRecoveryCoordinator.evaluateRecovery(
            nowMs = now,
            wasTrackingBeforeExit = true,
            restartTrackingIfKilled = true,
            lastHeartbeatMs = now - TrackingRecoveryCoordinator.HEARTBEAT_STALE_MS - 1L,
            consecutiveStaleTicks = TrackingRecoveryCoordinator.REQUIRED_CONSECUTIVE_STALE_TICKS,
            lastStopWasIntentional = false,
            canStartNow = true,
            strictPrereqsReady = true,
            exactAlarmAvailable = true,
            recoveryWindowStartMs = now - 5_000L,
            failureNotificationShown = false
        )

        assertTrue(decision.shouldKeepWatchdog)
        assertTrue(decision.shouldStartService)
        assertEquals(TrackingRecoveryCoordinator.RecoveryState.READY, decision.state)
    }

    @Test
    fun evaluateRecovery_keepsWatchdogWithoutStart_whenHeartbeatIsFresh() {
        val now = 10_000L
        val decision = TrackingRecoveryCoordinator.evaluateRecovery(
            nowMs = now,
            wasTrackingBeforeExit = true,
            restartTrackingIfKilled = true,
            lastHeartbeatMs = now - 1_000L,
            consecutiveStaleTicks = TrackingRecoveryCoordinator.REQUIRED_CONSECUTIVE_STALE_TICKS,
            lastStopWasIntentional = false,
            canStartNow = true,
            strictPrereqsReady = true,
            exactAlarmAvailable = true,
            recoveryWindowStartMs = now - 5_000L,
            failureNotificationShown = false
        )

        assertTrue(decision.shouldKeepWatchdog)
        assertFalse(decision.shouldStartService)
        assertEquals(TrackingRecoveryCoordinator.RecoveryState.HEALTHY, decision.state)
    }

    @Test
    fun evaluateRecovery_disablesWatchdog_whenStopWasIntentional() {
        val now = 10_000L
        val decision = TrackingRecoveryCoordinator.evaluateRecovery(
            nowMs = now,
            wasTrackingBeforeExit = true,
            restartTrackingIfKilled = true,
            lastHeartbeatMs = 0L,
            consecutiveStaleTicks = TrackingRecoveryCoordinator.REQUIRED_CONSECUTIVE_STALE_TICKS,
            lastStopWasIntentional = true,
            canStartNow = true,
            strictPrereqsReady = true,
            exactAlarmAvailable = true,
            recoveryWindowStartMs = now - 5_000L,
            failureNotificationShown = false
        )

        assertFalse(decision.shouldKeepWatchdog)
        assertFalse(decision.shouldStartService)
        assertEquals(TrackingRecoveryCoordinator.RecoveryState.DISABLED, decision.state)
    }

    @Test
    fun evaluateRecovery_keepsWatchdogWithoutStart_whenStrictPrereqsMissing() {
        val now = 10_000L
        val decision = TrackingRecoveryCoordinator.evaluateRecovery(
            nowMs = now,
            wasTrackingBeforeExit = true,
            restartTrackingIfKilled = true,
            lastHeartbeatMs = 0L,
            consecutiveStaleTicks = TrackingRecoveryCoordinator.REQUIRED_CONSECUTIVE_STALE_TICKS,
            lastStopWasIntentional = false,
            canStartNow = true,
            strictPrereqsReady = false,
            exactAlarmAvailable = false,
            recoveryWindowStartMs = now - 5_000L,
            failureNotificationShown = false
        )

        assertTrue(decision.shouldKeepWatchdog)
        assertFalse(decision.shouldStartService)
        assertEquals(TrackingRecoveryCoordinator.RecoveryState.BLOCKED_PREREQ, decision.state)
    }

    @Test
    fun evaluateRecovery_showsFailureNotification_afterSixtySeconds() {
        val now = 120_000L
        val decision = TrackingRecoveryCoordinator.evaluateRecovery(
            nowMs = now,
            wasTrackingBeforeExit = true,
            restartTrackingIfKilled = true,
            lastHeartbeatMs = 0L,
            consecutiveStaleTicks = TrackingRecoveryCoordinator.REQUIRED_CONSECUTIVE_STALE_TICKS,
            lastStopWasIntentional = false,
            canStartNow = false,
            strictPrereqsReady = true,
            exactAlarmAvailable = false,
            recoveryWindowStartMs = now - TrackingRecoveryCoordinator.RECOVERY_FAILURE_MS,
            failureNotificationShown = false
        )

        assertTrue(decision.shouldShowFailureNotification)
    }

    @Test
    fun evaluateRecovery_returnsThrottled_whenExactAlarmUnavailable() {
        val now = 50_000L
        val decision = TrackingRecoveryCoordinator.evaluateRecovery(
            nowMs = now,
            wasTrackingBeforeExit = true,
            restartTrackingIfKilled = true,
            lastHeartbeatMs = 0L,
            consecutiveStaleTicks = TrackingRecoveryCoordinator.REQUIRED_CONSECUTIVE_STALE_TICKS,
            lastStopWasIntentional = false,
            canStartNow = true,
            strictPrereqsReady = true,
            exactAlarmAvailable = false,
            recoveryWindowStartMs = now - 5_000L,
            failureNotificationShown = false
        )

        assertEquals(TrackingRecoveryCoordinator.RecoveryState.THROTTLED, decision.state)
        assertTrue(decision.shouldStartService)
    }

    @Test
    fun evaluateRecovery_doesNotStart_whenOnlyOneStaleTickObserved() {
        val now = 50_000L
        val decision = TrackingRecoveryCoordinator.evaluateRecovery(
            nowMs = now,
            wasTrackingBeforeExit = true,
            restartTrackingIfKilled = true,
            lastHeartbeatMs = now - TrackingRecoveryCoordinator.HEARTBEAT_STALE_MS - 1L,
            consecutiveStaleTicks = 1,
            lastStopWasIntentional = false,
            canStartNow = true,
            strictPrereqsReady = true,
            exactAlarmAvailable = true,
            recoveryWindowStartMs = now - 5_000L,
            failureNotificationShown = false
        )

        assertEquals(TrackingRecoveryCoordinator.RecoveryState.PENDING_STALE_CONFIRMATION, decision.state)
        assertFalse(decision.shouldStartService)
        assertFalse(decision.shouldShowFailureNotification)
        assertTrue(decision.shouldKeepWatchdog)
    }

    @Test
    fun evaluateRecovery_starts_whenStaleTickThresholdReached() {
        val now = 50_000L
        val decision = TrackingRecoveryCoordinator.evaluateRecovery(
            nowMs = now,
            wasTrackingBeforeExit = true,
            restartTrackingIfKilled = true,
            lastHeartbeatMs = now - TrackingRecoveryCoordinator.HEARTBEAT_STALE_MS - 1L,
            consecutiveStaleTicks = TrackingRecoveryCoordinator.REQUIRED_CONSECUTIVE_STALE_TICKS,
            lastStopWasIntentional = false,
            canStartNow = true,
            strictPrereqsReady = true,
            exactAlarmAvailable = true,
            recoveryWindowStartMs = now - 5_000L,
            failureNotificationShown = false
        )

        assertEquals(TrackingRecoveryCoordinator.RecoveryState.READY, decision.state)
        assertTrue(decision.shouldStartService)
        assertTrue(decision.shouldKeepWatchdog)
    }

    @Test
    fun evaluateRecovery_respectsCustomStaleTickThreshold() {
        val now = 50_000L
        val decision = TrackingRecoveryCoordinator.evaluateRecovery(
            nowMs = now,
            wasTrackingBeforeExit = true,
            restartTrackingIfKilled = true,
            lastHeartbeatMs = now - TrackingRecoveryCoordinator.HEARTBEAT_STALE_MS - 1L,
            consecutiveStaleTicks = 2,
            lastStopWasIntentional = false,
            canStartNow = true,
            strictPrereqsReady = true,
            exactAlarmAvailable = true,
            recoveryWindowStartMs = now - 5_000L,
            failureNotificationShown = false,
            requiredConsecutiveStaleTicks = 3
        )

        assertEquals(TrackingRecoveryCoordinator.RecoveryState.PENDING_STALE_CONFIRMATION, decision.state)
        assertFalse(decision.shouldStartService)
        assertTrue(decision.shouldKeepWatchdog)
    }

    @Test
    fun evaluateRecovery_doesNotShowFailure_beforeConfirmedRecoveryWindowExists() {
        val now = 120_000L
        val decision = TrackingRecoveryCoordinator.evaluateRecovery(
            nowMs = now,
            wasTrackingBeforeExit = true,
            restartTrackingIfKilled = true,
            lastHeartbeatMs = 0L,
            consecutiveStaleTicks = TrackingRecoveryCoordinator.REQUIRED_CONSECUTIVE_STALE_TICKS,
            lastStopWasIntentional = false,
            canStartNow = false,
            strictPrereqsReady = true,
            exactAlarmAvailable = true,
            recoveryWindowStartMs = 0L,
            failureNotificationShown = false
        )

        assertFalse(decision.shouldShowFailureNotification)
        assertEquals(TrackingRecoveryCoordinator.RecoveryState.BLOCKED_PREREQ, decision.state)
    }

    @Test
    fun sanitizeLastStartRequestMs_resetsFutureTimestamp() {
        val now = 10_000L
        val sanitized = TrackingRecoveryCoordinator.sanitizeLastStartRequestMs(
            nowMs = now,
            persistedLastStartRequestMs = now + 20_000L
        )
        assertEquals(0L, sanitized)
    }

    @Test
    fun shouldThrottleRestartRequest_notThrottledAfterSanitizedFutureTimestamp() {
        val now = 10_000L
        val sanitized = TrackingRecoveryCoordinator.sanitizeLastStartRequestMs(
            nowMs = now,
            persistedLastStartRequestMs = now + 20_000L
        )
        val throttled = TrackingRecoveryCoordinator.shouldThrottleRestartRequest(
            nowMs = now,
            lastStartRequestMs = sanitized
        )
        assertFalse(throttled)
    }
}
