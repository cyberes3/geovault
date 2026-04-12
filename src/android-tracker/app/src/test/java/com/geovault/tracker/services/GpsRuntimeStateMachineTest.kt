package com.geovault.tracker.services

import org.junit.Assert.assertEquals
import org.junit.Test

class GpsRuntimeStateMachineTest {

    @Test
    fun trackingStarted_movesToLocking() {
        val next = GpsRuntimeStateMachine.transition(
            GpsRuntimeState.INACTIVE,
            GpsRuntimeEvent.TRACKING_STARTED
        )
        assertEquals(GpsRuntimeState.LOCKING, next)
    }

    @Test
    fun fixAccepted_movesToRunning() {
        val next = GpsRuntimeStateMachine.transition(
            GpsRuntimeState.LOCKING,
            GpsRuntimeEvent.FIX_ACCEPTED
        )
        assertEquals(GpsRuntimeState.RUNNING, next)
    }

    @Test
    fun providerDisabled_movesToWaiting() {
        val next = GpsRuntimeStateMachine.transition(
            GpsRuntimeState.RUNNING,
            GpsRuntimeEvent.PROVIDER_DISABLED
        )
        assertEquals(GpsRuntimeState.WAITING_FOR_PROVIDER, next)
    }

    @Test
    fun providerEnabled_fromWaiting_movesToLocking() {
        val next = GpsRuntimeStateMachine.transition(
            GpsRuntimeState.WAITING_FOR_PROVIDER,
            GpsRuntimeEvent.PROVIDER_ENABLED
        )
        assertEquals(GpsRuntimeState.LOCKING, next)
    }

    @Test
    fun fallbackTimerArmed_movesToFallbackPending() {
        val next = GpsRuntimeStateMachine.transition(
            GpsRuntimeState.LOCKING,
            GpsRuntimeEvent.FALLBACK_TIMER_ARMED
        )
        assertEquals(GpsRuntimeState.FALLBACK_PENDING, next)
    }

    @Test
    fun fallbackEmitted_movesToRunning() {
        val next = GpsRuntimeStateMachine.transition(
            GpsRuntimeState.FALLBACK_PENDING,
            GpsRuntimeEvent.FALLBACK_EMITTED
        )
        assertEquals(GpsRuntimeState.RUNNING, next)
    }

    @Test
    fun pauseAndResume_motionTransitions() {
        val paused = GpsRuntimeStateMachine.transition(
            GpsRuntimeState.RUNNING,
            GpsRuntimeEvent.PAUSE_FOR_MOTION
        )
        val resumed = GpsRuntimeStateMachine.transition(
            paused,
            GpsRuntimeEvent.RESUME_FROM_MOTION
        )
        assertEquals(GpsRuntimeState.PAUSED_FOR_MOTION, paused)
        assertEquals(GpsRuntimeState.LOCKING, resumed)
    }

    @Test
    fun fastLockEvents_keepStateInLockingPath() {
        val started = GpsRuntimeStateMachine.transition(
            GpsRuntimeState.RUNNING,
            GpsRuntimeEvent.FAST_LOCK_STARTED
        )
        val timedOut = GpsRuntimeStateMachine.transition(
            started,
            GpsRuntimeEvent.FAST_LOCK_TIMEOUT
        )
        assertEquals(GpsRuntimeState.LOCKING, started)
        assertEquals(GpsRuntimeState.LOCKING, timedOut)
    }

    @Test
    fun providerDisabled_whilePaused_movesToWaitingPaused() {
        val next = GpsRuntimeStateMachine.transition(
            GpsRuntimeState.PAUSED_FOR_MOTION,
            GpsRuntimeEvent.PROVIDER_DISABLED
        )
        assertEquals(GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED, next)
    }

    @Test
    fun providerDisabled_fromWaitingPaused_staysWaitingPaused() {
        val next = GpsRuntimeStateMachine.transition(
            GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED,
            GpsRuntimeEvent.PROVIDER_DISABLED
        )
        assertEquals(GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED, next)
    }

    @Test
    fun providerDisabled_fromWaiting_staysWaiting() {
        val next = GpsRuntimeStateMachine.transition(
            GpsRuntimeState.WAITING_FOR_PROVIDER,
            GpsRuntimeEvent.PROVIDER_DISABLED
        )
        assertEquals(GpsRuntimeState.WAITING_FOR_PROVIDER, next)
    }

    @Test
    fun providerEnabled_fromWaitingPaused_restoresPaused() {
        val next = GpsRuntimeStateMachine.transition(
            GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED,
            GpsRuntimeEvent.PROVIDER_ENABLED
        )
        assertEquals(GpsRuntimeState.PAUSED_FOR_MOTION, next)
    }

    @Test
    fun pauseForMotion_fromWaitingProvider_movesToWaitingProviderPaused() {
        val next = GpsRuntimeStateMachine.transition(
            GpsRuntimeState.WAITING_FOR_PROVIDER,
            GpsRuntimeEvent.PAUSE_FOR_MOTION
        )
        assertEquals(GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED, next)
    }

    @Test
    fun resumeFromMotion_fromWaitingProviderPaused_movesToWaitingProvider() {
        val next = GpsRuntimeStateMachine.transition(
            GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED,
            GpsRuntimeEvent.RESUME_FROM_MOTION
        )
        assertEquals(GpsRuntimeState.WAITING_FOR_PROVIDER, next)
    }

    @Test
    fun fixRejected_fromWaitingProviderPaused_preservesState() {
        val next = GpsRuntimeStateMachine.transition(
            GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED,
            GpsRuntimeEvent.FIX_REJECTED
        )
        assertEquals(GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED, next)
    }

    @Test
    fun fixRejected_fromWaitingProvider_preservesState() {
        val next = GpsRuntimeStateMachine.transition(
            GpsRuntimeState.WAITING_FOR_PROVIDER,
            GpsRuntimeEvent.FIX_REJECTED
        )
        assertEquals(GpsRuntimeState.WAITING_FOR_PROVIDER, next)
    }

    @Test
    fun pauseForMotion_fromWaitingProviderPaused_staysWaitingProviderPaused() {
        val next = GpsRuntimeStateMachine.transition(
            GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED,
            GpsRuntimeEvent.PAUSE_FOR_MOTION
        )
        assertEquals(GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED, next)
    }

    @Test
    fun trackingStopped_fromFallbackPending_movesToInactive() {
        val next = GpsRuntimeStateMachine.transition(
            GpsRuntimeState.FALLBACK_PENDING,
            GpsRuntimeEvent.TRACKING_STOPPED
        )
        assertEquals(GpsRuntimeState.INACTIVE, next)
    }

    @Test
    fun fixRejected_fromFallbackPending_movesToLocking() {
        val next = GpsRuntimeStateMachine.transition(
            GpsRuntimeState.FALLBACK_PENDING,
            GpsRuntimeEvent.FIX_REJECTED
        )
        assertEquals(GpsRuntimeState.LOCKING, next)
    }

    @Test
    fun providerEnabled_fromRunning_keepsRunning() {
        val next = GpsRuntimeStateMachine.transition(
            GpsRuntimeState.RUNNING,
            GpsRuntimeEvent.PROVIDER_ENABLED
        )
        assertEquals(GpsRuntimeState.RUNNING, next)
    }

    @Test
    fun providerPauseResume_sequence_preservesPausedUntilMotionResume() {
        val providerDownWhileRunning = GpsRuntimeStateMachine.transition(
            GpsRuntimeState.RUNNING,
            GpsRuntimeEvent.PROVIDER_DISABLED
        )
        val pausedWhileProviderDown = GpsRuntimeStateMachine.transition(
            providerDownWhileRunning,
            GpsRuntimeEvent.PAUSE_FOR_MOTION
        )
        val providerReenabled = GpsRuntimeStateMachine.transition(
            pausedWhileProviderDown,
            GpsRuntimeEvent.PROVIDER_ENABLED
        )
        val resumedByMotion = GpsRuntimeStateMachine.transition(
            providerReenabled,
            GpsRuntimeEvent.RESUME_FROM_MOTION
        )

        assertEquals(GpsRuntimeState.WAITING_FOR_PROVIDER, providerDownWhileRunning)
        assertEquals(GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED, pausedWhileProviderDown)
        assertEquals(GpsRuntimeState.PAUSED_FOR_MOTION, providerReenabled)
        assertEquals(GpsRuntimeState.LOCKING, resumedByMotion)
    }
}
