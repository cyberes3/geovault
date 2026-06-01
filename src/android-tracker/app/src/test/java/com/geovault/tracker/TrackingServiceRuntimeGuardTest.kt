package com.geovault.tracker

import android.location.Location
import com.geovault.tracker.positioning.config.GpsRuntimeState
import com.geovault.tracker.positioning.FallbackTransitionPolicy
import com.geovault.tracker.positioning.GpsProviderWaitPolicy
import com.geovault.tracker.positioning.ObservedSpeedResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TrackingServiceRuntimeGuardTest {
    @Test
    fun waitingStateHelper_trueForBothWaitingStates() {
        assertTrue(GpsProviderWaitPolicy.isWaitingForProviderState(GpsRuntimeState.WAITING_FOR_PROVIDER))
        assertTrue(GpsProviderWaitPolicy.isWaitingForProviderState(GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED))
        assertFalse(GpsProviderWaitPolicy.isWaitingForProviderState(GpsRuntimeState.RUNNING))
    }

    @Test
    fun fallbackTransitionGuard_rejectsImplausibleJump() {
        val previous = Location("gps").apply {
            latitude = 0.0
            longitude = 0.0
            time = 1_000L
        }
        val candidate = Location("gps").apply {
            latitude = 1.0
            longitude = 1.0
            time = 1_500L
        }
        assertFalse(
            FallbackTransitionPolicy.shouldEmitFallbackForTransition(previous, candidate, 1_500L)
        )
    }

    @Test
    fun fallbackTransitionGuard_allowsReasonableMove() {
        val previous = Location("gps").apply {
            latitude = 10.0
            longitude = 10.0
            time = 10_000L
        }
        val candidate = Location("gps").apply {
            latitude = 10.00001
            longitude = 10.00001
            time = 15_000L
        }
        assertTrue(
            FallbackTransitionPolicy.shouldEmitFallbackForTransition(previous, candidate, 15_000L)
        )
    }

    @Test
    fun fallbackTransitionGuard_nullPrevious_allowsEmission() {
        val candidate = Location("gps").apply {
            latitude = 0.2
            longitude = 0.2
            time = 2_000L
        }
        assertTrue(FallbackTransitionPolicy.shouldEmitFallbackForTransition(null, candidate, 2_000L))
    }

    @Test
    fun fallbackTransitionGuard_rejectsHighSpeedOutsideBurstWindow() {
        val previous = Location("gps").apply {
            latitude = 0.0
            longitude = 0.0
            time = 1_000L
        }
        val candidate = Location("gps").apply {
            latitude = 0.01
            longitude = 0.01
            time = 21_000L
        }
        assertFalse(
            FallbackTransitionPolicy.shouldEmitFallbackForTransition(previous, candidate, 21_000L)
        )
    }

    @Test
    fun observedSpeed_prefersReportedSpeedWhenPresent() {
        val previous = Location("gps").apply {
            latitude = 0.0
            longitude = 0.0
            time = 1_000L
        }
        val candidate = Location("gps").apply {
            latitude = 0.001
            longitude = 0.0
            time = 2_000L
            speed = 0.05f
        }
        assertEquals(
            0.05f,
            ObservedSpeedResolver.resolveObservedSpeedMps(candidate, previous) ?: -1f,
            0.001f,
        )
    }

    @Test
    fun observedSpeed_usesImpliedSpeedWhenReportedSpeedMissing() {
        val previous = Location("gps").apply {
            latitude = 0.0
            longitude = 0.0
            time = 1_000L
        }
        val candidate = Location("gps").apply {
            latitude = 0.00001
            longitude = 0.0
            time = 2_000L
        }
        val observed = ObservedSpeedResolver.resolveObservedSpeedMps(candidate, previous)
        assertTrue((observed ?: 0f) > 0f)
    }
}
