package com.geovault.tracker

import android.location.Location
import com.geovault.tracker.services.GpsRuntimeState
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
        val service = TrackingService()
        val stateField = service.javaClass.getDeclaredField("gpsRuntimeState")
        stateField.isAccessible = true

        stateField.set(service, GpsRuntimeState.WAITING_FOR_PROVIDER)
        assertTrue(invokeIsWaitingForProviderState(service))

        stateField.set(service, GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED)
        assertTrue(invokeIsWaitingForProviderState(service))

        stateField.set(service, GpsRuntimeState.RUNNING)
        assertFalse(invokeIsWaitingForProviderState(service))
    }

    @Test
    fun fallbackTransitionGuard_rejectsImplausibleJump() {
        val service = TrackingService()
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
        assertFalse(invokeShouldEmitFallbackForTransition(service, previous, candidate, 1_500L))
    }

    @Test
    fun fallbackTransitionGuard_allowsReasonableMove() {
        val service = TrackingService()
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
        assertTrue(invokeShouldEmitFallbackForTransition(service, previous, candidate, 15_000L))
    }

    @Test
    fun fallbackTransitionGuard_nullPrevious_allowsEmission() {
        val service = TrackingService()
        val candidate = Location("gps").apply {
            latitude = 0.2
            longitude = 0.2
            time = 2_000L
        }
        assertTrue(invokeShouldEmitFallbackForTransition(service, null, candidate, 2_000L))
    }

    @Test
    fun fallbackTransitionGuard_rejectsHighSpeedOutsideBurstWindow() {
        val service = TrackingService()
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
        assertFalse(invokeShouldEmitFallbackForTransition(service, previous, candidate, 21_000L))
    }

    @Test
    fun observedSpeed_prefersReportedSpeedWhenPresent() {
        val service = TrackingService()
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
            invokeResolveObservedSpeedMps(service, candidate, previous) ?: -1f,
            0.001f
        )
    }

    @Test
    fun observedSpeed_usesImpliedSpeedWhenReportedSpeedMissing() {
        val service = TrackingService()
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

        val observed = invokeResolveObservedSpeedMps(service, candidate, previous)

        assertTrue((observed ?: 0f) > 0f)
    }

    private fun invokeIsWaitingForProviderState(service: TrackingService): Boolean {
        val method = service.javaClass.getDeclaredMethod("isWaitingForProviderState")
        method.isAccessible = true
        return method.invoke(service) as Boolean
    }

    private fun invokeShouldEmitFallbackForTransition(
        service: TrackingService,
        previous: Location?,
        candidate: Location,
        nowMs: Long
    ): Boolean {
        val method = service.javaClass.getDeclaredMethod(
            "shouldEmitFallbackForTransition",
            Location::class.java,
            Location::class.java,
            Long::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(service, previous, candidate, nowMs) as Boolean
    }

    private fun invokeResolveObservedSpeedMps(
        service: TrackingService,
        location: Location,
        referenceLocation: Location?
    ): Float? {
        val method = service.javaClass.getDeclaredMethod(
            "resolveObservedSpeedMps",
            Location::class.java,
            Location::class.java
        )
        method.isAccessible = true
        return method.invoke(service, location, referenceLocation) as Float?
    }
}
