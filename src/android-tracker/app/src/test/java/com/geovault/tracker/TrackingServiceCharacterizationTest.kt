package com.geovault.tracker

import com.geovault.tracker.services.GpsRuntimeState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TrackingServiceCharacterizationTest {
    @Test
    fun startupCommandRouting_preservesServiceEntrySemantics() {
        assertTrue(
            TrackingService.requiresForegroundPromotion(
                TrackingService.resolveStartupCommandPath(TrackingService.ACTION_START)
            )
        )
        assertFalse(
            TrackingService.requiresForegroundPromotion(
                TrackingService.resolveStartupCommandPath(TrackingService.ACTION_LOCATION_UPDATE)
            )
        )
        assertFalse(
            TrackingService.requiresForegroundPromotion(
                TrackingService.resolveStartupCommandPath(null)
            )
        )
    }

    @Test
    fun expectsActiveFixDelivery_onlyForCollectingStates() {
        val service = TrackingService()
        setTracking(service, true)

        setGpsState(service, GpsRuntimeState.RUNNING)
        assertTrue(invokeExpectsActiveFixDelivery(service))

        setGpsState(service, GpsRuntimeState.PAUSED_FOR_MOTION)
        assertFalse(invokeExpectsActiveFixDelivery(service))

        setGpsState(service, GpsRuntimeState.WAITING_FOR_PROVIDER)
        assertFalse(invokeExpectsActiveFixDelivery(service))

        setTracking(service, false)
        setGpsState(service, GpsRuntimeState.RUNNING)
        assertFalse(invokeExpectsActiveFixDelivery(service))
    }

    private fun setTracking(service: TrackingService, value: Boolean) {
        val field = service.javaClass.getDeclaredField("isTracking")
        field.isAccessible = true
        field.set(service, value)
    }

    private fun setGpsState(service: TrackingService, state: GpsRuntimeState) {
        val field = service.javaClass.getDeclaredField("gpsRuntimeState")
        field.isAccessible = true
        field.set(service, state)
    }

    private fun invokeExpectsActiveFixDelivery(service: TrackingService): Boolean {
        val method = service.javaClass.getDeclaredMethod("expectsActiveFixDelivery")
        method.isAccessible = true
        return method.invoke(service) as Boolean
    }
}
