package com.geovault.tracker.positioning.motion

import com.geovault.tracker.positioning.config.GpsRuntimeState
import org.junit.Assert.assertEquals
import org.junit.Test

class MotionStateTest {

    @Test
    fun waitingForProvider_winsOverPauseAndProbe() {
        assertEquals(
            MotionState.WaitingForProvider,
            motionStateOf(GpsRuntimeState.WAITING_FOR_PROVIDER, probeActive = true),
        )
        assertEquals(
            MotionState.WaitingForProvider,
            motionStateOf(GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED, probeActive = false),
        )
    }

    @Test
    fun probingFreshness_winsOverPausedAndCollecting() {
        assertEquals(
            MotionState.ProbingFreshness,
            motionStateOf(GpsRuntimeState.PAUSED_FOR_MOTION, probeActive = true),
        )
        assertEquals(
            MotionState.ProbingFreshness,
            motionStateOf(GpsRuntimeState.RUNNING, probeActive = true),
        )
    }

    @Test
    fun pausedStationary_isPausedForMotionWithoutProbe() {
        assertEquals(
            MotionState.PausedStationary,
            motionStateOf(GpsRuntimeState.PAUSED_FOR_MOTION, probeActive = false),
        )
    }

    @Test
    fun collecting_coversLockFallbackRunningAndInactive() {
        for (state in listOf(
            GpsRuntimeState.INACTIVE,
            GpsRuntimeState.RUNNING,
            GpsRuntimeState.LOCKING,
            GpsRuntimeState.FALLBACK_PENDING,
        )) {
            assertEquals(state.name, MotionState.Collecting, motionStateOf(state, probeActive = false))
        }
    }
}
