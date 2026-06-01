package com.geovault.tracker.positioning

import com.geovault.tracker.positioning.config.GpsRuntimeEvent
import com.geovault.tracker.positioning.config.GpsRuntimeState
import com.geovault.tracker.positioning.config.GpsRuntimeStateMachine
import com.geovault.tracker.services.TrackingRuntimeStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PositioningRuntimeProviderCharacterizationTest {

    @Test
    fun providerDisabledFromRunning_entersWaitingState() {
        var state = GpsRuntimeState.RUNNING
        state = GpsRuntimeStateMachine.transition(state, GpsRuntimeEvent.PROVIDER_DISABLED)
        assertEquals(GpsRuntimeState.WAITING_FOR_PROVIDER, state)
        assertFalse(
            LocationRequestController.expectsActiveFixDelivery(
                isTracking = true,
                gpsRuntimeState = state,
            ),
        )
    }

    @Test
    fun trackingRuntimeStateStore_reflectsGpsCollectingFlag() {
        val before = TrackingRuntimeStateStore.state.value
        try {
            TrackingRuntimeStateStore.update {
                it.copy(
                    isRunning = true,
                    selectedTrackerId = "t1",
                    recordingRuntime = it.recordingRuntime.copy(
                        sessionActive = true,
                        gpsCollecting = true,
                        pausedForMotion = false,
                    ),
                )
            }
            assertTrue(TrackingRuntimeStateStore.state.value.gpsCollecting)
            TrackingRuntimeStateStore.update {
                it.copy(
                    recordingRuntime = it.recordingRuntime.copy(
                        gpsCollecting = false,
                        pausedForMotion = true,
                    ),
                )
            }
            assertFalse(TrackingRuntimeStateStore.state.value.gpsCollecting)
        } finally {
            TrackingRuntimeStateStore.update { before }
        }
    }
}
