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
}
