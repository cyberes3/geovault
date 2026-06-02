package com.geovault.tracker.positioning

import com.geovault.tracker.positioning.config.GpsRuntimeState
import com.geovault.tracker.services.RuntimeLocationGateInput
import com.geovault.tracker.services.TrackingRuntimeOrchestrator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixIngestCharacterizationTest {

    @Test
    fun ingestGate_blocksWhenNotTracking() {
        assertFalse(
            TrackingRuntimeOrchestrator.shouldProcessLocationUpdate(
                RuntimeLocationGateInput(
                    isTracking = false,
                    gpsState = GpsRuntimeState.RUNNING,
                    allowWhenGpsPaused = false,
                ),
            ),
        )
    }

    @Test
    fun ingestGate_blocksWaitingForProviderUnlessBypassed() {
        assertFalse(
            TrackingRuntimeOrchestrator.shouldProcessLocationUpdate(
                RuntimeLocationGateInput(
                    isTracking = true,
                    gpsState = GpsRuntimeState.WAITING_FOR_PROVIDER,
                    allowWhenGpsPaused = false,
                ),
            ),
        )
        assertTrue(
            TrackingRuntimeOrchestrator.shouldProcessLocationUpdate(
                RuntimeLocationGateInput(
                    isTracking = true,
                    gpsState = GpsRuntimeState.WAITING_FOR_PROVIDER,
                    allowWhenGpsPaused = true,
                ),
            ),
        )
    }
}
