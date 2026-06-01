package com.geovault.tracker.positioning

import com.geovault.tracker.positioning.config.GpsRuntimeEvent
import com.geovault.tracker.positioning.config.GpsRuntimeState
import com.geovault.tracker.positioning.config.GpsRuntimeStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Recovery paths that combine GPS FSM state with fallback / fix-delivery policy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RecoveryCharacterizationTest {

    @Test
    fun fallbackTimerArmed_entersFallbackPendingFromLocking() {
        val next = GpsRuntimeStateMachine.transition(
            GpsRuntimeState.LOCKING,
            GpsRuntimeEvent.FALLBACK_TIMER_ARMED,
        )
        assertEquals(GpsRuntimeState.FALLBACK_PENDING, next)
    }

    @Test
    fun fixDelivery_notExpectedWhilePausedOrFallbackPending() {
        assertFalse(
            LocationRequestController.expectsActiveFixDelivery(
                isTracking = true,
                gpsRuntimeState = GpsRuntimeState.PAUSED_FOR_MOTION,
            ),
        )
        assertTrue(
            LocationRequestController.expectsActiveFixDelivery(
                isTracking = true,
                gpsRuntimeState = GpsRuntimeState.FALLBACK_PENDING,
            ),
        )
        assertTrue(
            LocationRequestController.expectsActiveFixDelivery(
                isTracking = true,
                gpsRuntimeState = GpsRuntimeState.RUNNING,
            ),
        )
    }

    @Test
    fun fallbackPersistence_allowsFirstPointWithoutPrevious() {
        assertTrue(
            FallbackPersistencePolicy.shouldPersistFallbackPoint(
                previousAcceptedLocation = null,
                fallbackLocation = android.location.Location("gps").apply {
                    latitude = 1.0
                    longitude = 2.0
                    time = 1_000L
                    accuracy = 30f
                },
            ),
        )
    }
}
