package com.geovault.tracker.positioning

import com.geovault.tracker.positioning.config.GpsRuntimeState
import com.geovault.tracker.positioning.RuntimeLocationGateInput
import com.geovault.tracker.positioning.LocationUpdateGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixIngestCharacterizationTest {

    @Test
    fun ingestGate_blocksWhenNotTracking() {
        assertFalse(
            LocationUpdateGate.shouldProcessLocationUpdate(
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
            LocationUpdateGate.shouldProcessLocationUpdate(
                RuntimeLocationGateInput(
                    isTracking = true,
                    gpsState = GpsRuntimeState.WAITING_FOR_PROVIDER,
                    allowWhenGpsPaused = false,
                ),
            ),
        )
        assertTrue(
            LocationUpdateGate.shouldProcessLocationUpdate(
                RuntimeLocationGateInput(
                    isTracking = true,
                    gpsState = GpsRuntimeState.WAITING_FOR_PROVIDER,
                    allowWhenGpsPaused = true,
                ),
            ),
        )
    }
}
